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
        /** The whole-bill discount actually taken. Zero under item-wise. */
        val discount: Double,
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
        /**
         * The further deduction lines' own item-wise discounts still owe, on top of
         * [taxable] and the taxes - a post-tax item-wise discount, where the reported
         * taxable value stays the full pre-discount one. Zero in every other case.
         */
        val itemwiseDiscount: Double,
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
     * Bill-wise pre-tax: this line's share of the bill discount, split by its share of
     * the subtotal. Bill-wise post-tax: nothing here - that deduction happens once
     * against the bill total, not per line, or it would come off twice.
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
        return if (cfg.discountPreTax && subtotal > 0.0) gross / subtotal * billDiscount else 0.0
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
     * What a whole-bill discount is a percentage OF: the bill with its tax already on,
     * since a bill-wise discount is taken off after tax. 20% off a 110.00 sale
     * carrying 5.50 of GST is 23.10, not 22.00.
     *
     * Under inclusive pricing this equals the listed subtotal - the price already
     * carries its tax - so the two only differ when tax is added on top.
     *
     * Cannot recurse into [billDiscount]: the lines are priced here with no discount
     * at all.
     */
    fun discountBase(lines: List<Line>, cfg: Config): Double {
        val sub = subtotal(lines)
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
        if (cfg.taxEnabled) {
            val chargesPrincipal = charges.sumOf { it.amount }
            if (chargesPrincipal > 0.0 && sub > 0.0) {
                lines.forEach { line ->
                    val share = line.gross / sub * chargesPrincipal
                    cgst += GstCalculator.taxAmount(share, line.cgstRate)
                    sgst += GstCalculator.taxAmount(share, line.sgstRate)
                    vat += GstCalculator.taxAmount(share, line.vatRate)
                }
            }
        }
        // What a post-tax item-wise discount still owes: BillPricing reports the full
        // pre-discount taxable value and tax for those, and puts the reduction in the
        // line total instead. The difference is that reduction.
        val itemwise = priced.sumOf { (it.taxable + it.cgst + it.sgst + it.vat) - it.itemTotal }
            .coerceAtLeast(0.0)

        // Pre-tax: the discount is already inside each line's taxable value. Post-tax:
        // it comes off once, here, after tax - which is why it is NOT spread per line
        // above.
        val goods = if (cfg.discountPreTax) {
            (taxable + cgst + sgst + vat - itemwise).coerceAtLeast(0.0)
        } else {
            (taxable + cgst + sgst + vat - disc - itemwise).coerceAtLeast(0.0)
        }

        return Totals(
            subtotal = BillRounding.toPaise(sub),
            discount = disc,
            taxable = BillRounding.toPaise(taxable),
            cgst = BillRounding.toPaise(cgst),
            sgst = BillRounding.toPaise(sgst),
            vat = BillRounding.toPaise(vat),
            itemwiseDiscount = BillRounding.toPaise(itemwise),
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
