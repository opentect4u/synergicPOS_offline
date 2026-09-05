package com.example.synergic_pos_offline.utils

import com.example.synergic_pos_offline.database.ChargeDao

/**
 * The whole discount-and-tax calculation for a cart, in one place.
 *
 * This is the grocery till's arithmetic, lifted out of the screens that grew it
 * (PosBillingFragment and PosCheckoutFragment) so the restaurant can bill the same
 * way. Those two are deliberately left alone - a working till is not worth
 * refactoring to share code - so what lives here is a faithful RESTATEMENT of their
 * rules rather than a call into them. Every rule below is written to match; where the
 * two ever disagree, they are the reference and this is the bug.
 *
 * It prices a line through [BillPricing], the same function `BillDao` writes bills
 * with, so what a screen quotes and what gets stored come from one calculation.
 *
 * ## The four things Tax Settings decides
 *
 * - **Tax on or off** - a line is taxed at whatever rates it actually carries
 *   (GST or VAT is a fact about the product, not a store-wide choice - see
 *   [GstCalculator.regimeOf]); this only decides whether any tax applies at all.
 * - **Inclusive or exclusive** - whether the listed price already contains its tax.
 * - **Item-wise or bill-wise discount** - a discount configured per product, or one
 *   figure entered against the whole bill. They are mutually exclusive.
 * - **Pre-tax or post-tax** - whether the discount reduces the taxable value, or
 *   comes off the taxed price with tax still reported on the full amount.
 */
object CartMath {

    /** One cart line, as the arithmetic needs it. */
    data class Line(
        val qty: Double,
        val rate: Double,
        val cgstRate: Double = 0.0,
        val sgstRate: Double = 0.0,
        val vatRate: Double = 0.0,
        /** The product's own pre-configured discount, for item-wise mode. */
        val discValue: Double = 0.0,
        /** "A" for a flat amount, anything else for a percentage; null for none. */
        val discType: String? = null
    ) {
        /** Quantity x listed rate - what the line reads as before anything is done to it. */
        val gross: Double get() = rate * qty
    }

    /** Tax Settings, resolved once and handed to every call. */
    data class Config(
        val taxEnabled: Boolean,
        val inclusive: Boolean,
        val discountPreTax: Boolean,
        val itemwiseDiscount: Boolean,
        /** Whether a bill-wise discount box applies at all (Discount on + Bill wise). */
        val billwiseDiscount: Boolean
    )

    /** Everything a screen or a bill needs to show, all to the paisa. */
    data class Totals(
        /** Sum of the listed line amounts, before anything. */
        val subtotal: Double,
        /**
         * What the customer was actually discounted, in the same denomination as the
         * listed price they were quoted - never the GST-filing figure worked out
         * against a line's pre-tax base (see [GstCalculator.itemDiscountAgainstRawBase]),
         * which reads smaller once tax is stripped back out of it. One number, correct
         * under all four combinations of item/bill-wise and pre/post-tax - see [totals].
         */
        val discount: Double,
        /**
         * The raw whole-bill discount [billDiscount] itself worked out - against the
         * bill's own pre-tax base, zero under item-wise (nothing left for a bill
         * figure to add there). NOT for display: this is the shape a caller spreads
         * per line with (see [lineDiscount]'s bill-wise branch) so that spread and
         * what [totals] priced every line with agree exactly - [discount] above,
         * being a different denomination for a post-tax bill-wise discount, would
         * spread a different figure than the one every line was actually priced
         * against.
         */
        val billWiseDiscount: Double,
        /** What tax was charged on the GOODS, across the bill - excludes [charges]. */
        val taxable: Double,
        /**
         * CGST/SGST/VAT actually charged on the bill - the goods' own tax, plus
         * whatever tax [charges] carry too (see [totals]). Not split apart from
         * one another: a charge is taxed at whatever rate(s) the goods themselves
         * carry, not a rate of its own, so there is nothing to show separately.
         */
        val cgst: Double,
        val sgst: Double,
        val vat: Double,
        /** The taxed goods with every discount off: what is owed for what was sold. */
        val goods: Double,
        /** The section's flat service charge, where the caller applies one. */
        val service: Double,
        /** The shop's own extra charges - see ChargeDao. */
        val charges: List<ChargeDao.Applied>
    ) {
        val tax: Double get() = cgst + sgst + vat
        val chargesTotal: Double get() = BillRounding.toPaise(charges.sumOf { it.amount })

        /**
         * What the bill comes to, before any round-off.
         *
         * Service and extra charges' own PRINCIPAL join LAST, on top of the taxed
         * goods - they are the shop's own additions rather than part of what was
         * sold, so no discount is worked out from them, and [chargesTotal] here is
         * still the bare, untaxed figure. Their TAX, though, is already inside
         * [goods] by way of the inflated [cgst]/[sgst]/[vat] - see [totals] - so it
         * is not added again here.
         */
        val total: Double get() = BillRounding.toPaise(goods + service + chargesTotal)
    }

    /**
     * The combined rate a line is stripped down by, for working out its pre-tax base -
     * whatever the line itself carries (a product has GST rates or a VAT rate, never
     * both, so summing all three is exactly picking whichever one it has), gated by
     * whether tax is on at all. Matches [BillPricing]'s own `combinedRate`.
     */
    fun rateOf(line: Line, cfg: Config): Double =
        if (cfg.taxEnabled) line.cgstRate + line.sgstRate + line.vatRate else 0.0

    /**
     * The discount for one line, expressed against its raw pre-tax base - the shape
     * [BillPricing] and `td_bill_items.discount_amount` both take.
     *
     * Item-wise: the product's own discount, converted to that shape.
     *
     * Bill-wise pre-tax: this line's share of the bill discount, split by its share
     * of the subtotal.
     *
     * Bill-wise post-tax, INCLUSIVE: GST is charged on the discounted value here
     * too, the same rule an item-wise post-tax discount already follows (see
     * [GstCalculator.priceItem]'s own note) - the line's MRP-denominated share
     * ([share]) is converted to this raw, pre-tax-base shape by dividing out the
     * line's own tax factor, the same conversion
     * [GstCalculator.itemDiscountAgainstRawBase]'s own inclusive branch applies to
     * a single product's discount.
     *
     * Bill-wise post-tax, EXCLUSIVE: nothing here - that deduction happens once
     * against the bill total instead (see [totals]'s own note on [Totals.goods]),
     * since an exclusive line's raw base already IS its listed rate, with no
     * separate taxed price for "post-tax" to mean anything different against.
     */
    fun lineDiscount(line: Line, cfg: Config, subtotal: Double, billDiscount: Double): Double {
        val gross = line.gross
        if (cfg.itemwiseDiscount && line.discValue > 0.0 && line.discType != null) {
            val mode = if (line.discType == "A") GstCalculator.DiscountMode.AMOUNT
            else GstCalculator.DiscountMode.PERCENT
            return GstCalculator.itemDiscountAgainstRawBase(
                gross, rateOf(line, cfg), cfg.inclusive, cfg.discountPreTax, mode, line.discValue
            )
        }
        if (subtotal <= 0.0) return 0.0
        val share = gross / subtotal * billDiscount
        return when {
            cfg.discountPreTax -> share
            cfg.inclusive -> share / (1.0 + rateOf(line, cfg) / 100.0)
            else -> 0.0
        }
    }

    /** One line priced, with [billDiscount] already spread where that applies. */
    fun priceLine(line: Line, cfg: Config, subtotal: Double, billDiscount: Double): BillPricing.Line =
        BillPricing.price(
            rate = line.rate,
            quantity = line.qty,
            cgstRate = line.cgstRate,
            sgstRate = line.sgstRate,
            vatRate = line.vatRate,
            discountAmount = lineDiscount(line, cfg, subtotal, billDiscount),
            taxEnabled = cfg.taxEnabled,
            inclusive = cfg.inclusive,
            discountPreTax = cfg.discountPreTax
        )

    /** Sum of the listed line amounts. */
    fun subtotal(lines: List<Line>): Double = lines.sumOf { it.gross }

    /**
     * What a whole-bill discount is a percentage OF - matching Discount Position the
     * same way an item-wise one does (see [CartMath.Totals.discount]'s own doc): a
     * POST-tax discount is taken off after tax, so 20% off a 110.00 sale carrying
     * 5.50 of GST is 23.10, not 22.00; a PRE-tax one is a share of the raw listed
     * subtotal instead, before any tax is added on top of it - 5% off a ₹600 +
     * ₹200 exclusive sale is ₹40.00, not ₹42.00 off the ₹840 those two lines come
     * to once taxed.
     *
     * Under INCLUSIVE pricing the two coincide (the price already carries its tax,
     * so there is no separate "before tax is added" figure to differ from), which
     * is why only the exclusive, pre-tax case needs its own branch here.
     *
     * Cannot recurse into [billDiscount]: the lines are priced here with no discount
     * at all.
     */
    fun discountBase(lines: List<Line>, cfg: Config): Double {
        val sub = subtotal(lines)
        if (!cfg.inclusive && cfg.discountPreTax) return sub
        return lines.sumOf { l ->
            val p = priceLine(l, cfg, sub, 0.0)
            p.taxable + p.cgst + p.sgst + p.vat
        }
    }

    /**
     * The whole-bill discount in rupees, from what was typed. Zero under item-wise,
     * where each line carries its own and there is nothing left for a bill figure to
     * add. Never more than the bill it is coming off.
     */
    fun billDiscount(
        lines: List<Line>, cfg: Config, mode: GstCalculator.DiscountMode, value: Double
    ): Double {
        if (cfg.itemwiseDiscount || !cfg.billwiseDiscount) return 0.0
        val base = discountBase(lines, cfg)
        return BillRounding.toPaise(
            GstCalculator.discountAmount(base, mode, value).coerceIn(0.0, base)
        )
    }

    /**
     * The whole calculation.
     *
     * [service] is added by the caller's own rule (the restaurant's section charge);
     * [charges] are the shop's extra charges, already worked out against the pre-tax
     * item total - see ChargeDao. Their principal is untaxed itself, but is not tax-
     * exempt: GST/VAT is charged on it too, at whatever rate(s) the lines it is
     * spread across carry - see the note inside this function.
     */
    fun totals(
        lines: List<Line>,
        cfg: Config,
        mode: GstCalculator.DiscountMode,
        value: Double,
        service: Double = 0.0,
        charges: List<ChargeDao.Applied> = emptyList()
    ): Totals {
        val sub = subtotal(lines)
        val disc = billDiscount(lines, cfg, mode, value)
        val priced = lines.map { priceLine(it, cfg, sub, disc) }

        val taxable = priced.sumOf { it.taxable }
        var cgst = priced.sumOf { it.cgst }
        var sgst = priced.sumOf { it.sgst }
        var vat = priced.sumOf { it.vat }
        // The charge's own tax, tracked apart from cgst/sgst/vat above as well as
        // folded into them - the totals need it for reporting, [goods] below needs
        // it kept clean of the same rounding [itemwise] avoids (see its own note).
        var chargeTax = 0.0

        // The shop's own extra charges are taxable too, at the SAME rate(s) the
        // goods on this bill carry - not a rate of their own. Spread across the
        // lines by each one's share of the gross subtotal, exactly the way a
        // bill-wise discount is spread (see lineDiscount), then taxed at THAT
        // line's own cgstRate/sgstRate/vatRate - not a blended rate, the same
        // distinction BillPricing draws for the goods themselves.
        //
        // Folded straight into cgst/sgst/vat rather than kept apart, so the
        // charge's tax comes out of the bill's existing GST/VAT figure, not a
        // column of its own. The charge's PRINCIPAL is untouched here: it still
        // joins the bill once, on its own, via Totals.total's chargesTotal - only
        // the tax ON it is added, so nothing is counted twice.
        //
        // Tax off means the till charges no tax at all, whatever rate a line carries
        // on file - see BillPricing.price's own note on this - so a charge is not
        // taxed there either.
        //
        // Nor is it taxed under MRP (inclusive) Post-Tax: the charge's principal
        // still joins the bill once, untaxed, via Totals.total's chargesTotal, but
        // nothing further is added to cgst/sgst/vat for it - a ₹40 service charge
        // on an inclusive Post-Tax sale is exactly ₹40, not ₹42 with GST folded in
        // on top of it.
        if (cfg.taxEnabled && !(cfg.inclusive && !cfg.discountPreTax)) {
            val chargesPrincipal = charges.sumOf { it.amount }
            if (chargesPrincipal > 0.0 && sub > 0.0) {
                lines.forEach { line ->
                    val share = line.gross / sub * chargesPrincipal
                    val c = GstCalculator.taxAmount(share, line.cgstRate)
                    val s = GstCalculator.taxAmount(share, line.sgstRate)
                    val v = GstCalculator.taxAmount(share, line.vatRate)
                    cgst += c; sgst += s; vat += v
                    chargeTax += c + s + v
                }
            }
        }
        // What the customer was actually discounted, against whichever price
        // Discount Position says the product's own configured discount is a share
        // of (see [GstCalculator.priceItem]'s own note): the rate itself
        // ([Line.gross]) for an inclusive line (already the full listed price,
        // whichever position) or an exclusive pre-tax one (the discount is
        // measured against the ex-tax rate as typed in); the rate WITH its own tax
        // added for an exclusive post-tax one, since "post-tax" means the discount
        // comes off a price that already has tax figured into it.
        //
        // NOT derived from the gap between a line's listed, taxed price and its
        // itemTotal regardless of position: that grosses an EXCLUSIVE PRE-TAX
        // line's discount up by its own tax on top of the discount itself - 5%
        // off a ₹600 exclusive pre-tax line came back as ₹31.50 that way, when the
        // operator configured (and the DISC column beside it prints) ₹30.00,
        // exactly 5% of the ₹600 that was actually typed in - pre-tax has nothing
        // to do with a taxed price at all.
        //
        // Bill-wise is a different, already-correct calculation - see
        // [billWiseDiscount]'s own doc - so item-wise is the only case this
        // covers; bill-wise passes [disc] straight through unchanged.
        val listedDiscount = if (cfg.itemwiseDiscount) {
            lines.sumOf { line ->
                if (line.discValue > 0.0 && line.discType != null) {
                    val mode = if (line.discType == "A") GstCalculator.DiscountMode.AMOUNT
                    else GstCalculator.DiscountMode.PERCENT
                    val postTaxExclusive = !cfg.inclusive && !cfg.discountPreTax
                    val base = if (postTaxExclusive) {
                        line.gross + GstCalculator.taxAmount(line.gross, rateOf(line, cfg))
                    } else line.gross
                    GstCalculator.discountAmount(base, mode, line.discValue)
                } else 0.0
            }
        } else disc

        // The taxed goods with every discount off, built from each line's own
        // already-exact itemTotal (see BillPricing.price) plus the charge's tax -
        // not reconstructed from taxable + cgst + sgst + vat, which sums each
        // line's tax separately rounded and can differ from that line's own
        // itemTotal by the width of that rounding, the same reason [itemwise] is
        // worked out the way it is above.
        //
        // A bill-wise POST-TAX EXCLUSIVE discount is the one reduction not already
        // inside any line's itemTotal - lineDiscount leaves an exclusive line
        // untouched there, since it comes off the bill's total once instead - so it
        // alone still needs subtracting here. Every other combination was already
        // spread into each line's own discountAmount before pricing (pre-tax always;
        // post-tax INCLUSIVE too - see lineDiscount's own note), so itemTotal
        // already carries it and nothing further is owed.
        val goods = (
            priced.sumOf { it.itemTotal } + chargeTax -
                (if (cfg.discountPreTax || cfg.inclusive) 0.0 else disc)
        ).coerceAtLeast(0.0)

        return Totals(
            subtotal = BillRounding.toPaise(sub),
            discount = BillRounding.toPaise(listedDiscount.coerceAtLeast(0.0)),
            billWiseDiscount = disc,
            taxable = BillRounding.toPaise(taxable),
            cgst = BillRounding.toPaise(cgst),
            sgst = BillRounding.toPaise(sgst),
            vat = BillRounding.toPaise(vat),
            goods = BillRounding.toPaise(goods),
            service = BillRounding.toPaise(service),
            charges = charges
        )
    }

    /**
     * The discount as a percentage of what it came off, for the record written to the
     * bill. The arithmetic always works from the amount, whichever way it was entered.
     */
    fun discountPercent(lines: List<Line>, cfg: Config, discount: Double): Double {
        val base = discountBase(lines, cfg)
        return if (base > 0.0) BillRounding.toPaise(discount / base * 100.0) else 0.0
    }
}
