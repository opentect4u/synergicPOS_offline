package com.example.synergic_pos_offline.utils

/**
 * The operator's manual, shown from About App > User Manual.
 *
 * ## Why the text is in here rather than in a file
 *
 * This is an OFFLINE till. A manual that lives on a website is unreadable in the shop
 * that most needs it - the one with no connection - and a PDF in the assets folder
 * needs a PDF reader installed, which a locked-down POS device often has not got. Text
 * compiled into the app is readable on every device, on the day it is installed, with
 * nothing else present.
 *
 * ## Why it is STRUCTURE and not formatted text
 *
 * The first version of this was pre-formatted strings, laid out with spaces and read
 * back in a monospace face. That is a typewriter's answer: it fixes the alignment at
 * one column width, so the same page is cramped on a phone and lost in white space on
 * a till, and every heading and step reads at the same weight as the prose around it.
 *
 * A chapter is a list of [Block]s instead - this is a heading, this is a step, this is
 * a caution - and the screen decides how each should look at the size it has. The
 * manual says what it MEANS; [com.example.synergic_pos_offline.fragments.UserManualFragment]
 * says what it looks like, and changing the type scale is then one change in one place.
 */
object UserManual {

    /** Bumped when the text changes materially, so a printed copy can be dated. */
    const val VERSION = "2026-09-22"

    /** One piece of a chapter. What it IS, not what it looks like - see the class note. */
    sealed class Block {
        /** A section title inside a chapter. */
        data class Heading(val text: String) : Block()

        /** Ordinary prose. */
        data class Para(val text: String) : Block()

        /** An ordered procedure - do this, then this. Numbered on screen. */
        data class Steps(val items: List<String>) : Block()

        /** An unordered list - options, parts, things to check. */
        data class Bullets(val items: List<String>) : Block()

        /**
         * Something that costs money or data if it is got wrong.
         *
         * Its own kind rather than a paragraph starting "Note:", so it can be set apart
         * on screen. A warning that reads like the sentence before it is a warning that
         * gets skimmed.
         */
        data class Caution(val text: String) : Block()
    }

    private val GETTING_STARTED = listOf(
        Block.Para(
            "A new install is deliberately empty - no demo products, no sample bills, " +
                "no test customers. Nothing has to be cleaned out before the shop opens, " +
                "but nothing works until the masters below are filled in."
        ),
        Block.Heading("Set the till up in this order"),
        Block.Para(
            "The order matters: each step needs the one before it. A product cannot be " +
                "saved without a category and a unit to put it in."
        ),
        Block.Steps(
            listOf(
                "Register the shop and sign in. The first user created is the admin.",
                "Settings > General Settings > Mode. Grocery, Restaurant or Calculator. " +
                    "This decides the whole menu and the sale screen, so it is the first " +
                    "real choice. Changing it later signs you out and clears the sale " +
                    "data, so make it now.",
                "Settings > Tax Settings. Whether tax is charged at all, GST or VAT, " +
                    "and whether your listed prices already include it. Also where a " +
                    "discount falls - before tax or after - which changes what a " +
                    "discounted bill comes to.",
                "Settings > Printer Settings. Add each printer against its purpose: " +
                    "BILL for receipts, KOT for the kitchen, BARCODE for labels.",
                "Master > Database Settings. Category/Department first, then Units and " +
                    "Rate Name, then Products.",
                "Master > Header & Footer. The lines that print above and below a bill - " +
                    "shop name, address, GSTIN, a thank-you."
            )
        ),
        Block.Para(
            "The till can sell as soon as step 5 has products in it. Everything else can " +
                "wait until it is wanted."
        ),
        Block.Heading("Who can see what"),
        Block.Para(
            "Master, Settings, Reports and About App are each granted per user on the " +
                "Add/Edit User form. A cashier is usually given none of them, so their " +
                "till opens on the sale screen and the drawer offers little else."
        ),
        Block.Caution(
            "Take a backup before changing the Mode, before restoring, and before " +
                "updating the app. About App > Manual Backup writes the whole database " +
                "to a file you choose."
        )
    )

    private val SELLING = listOf(
        Block.Heading("The sale screen"),
        Block.Para(
            "Products on the left, the running bill on the right. An item can be added " +
                "three ways: tap its tile, scan its barcode, or type a name, code or " +
                "barcode into the search box."
        ),
        Block.Bullets(
            listOf(
                "Tap a line already in the bill to change its quantity or remove it.",
                "A product sold by weight or part-units opens a popup for the quantity " +
                    "rather than adding one of it.",
                "Hold parks the whole sale so the next customer can be served; Held " +
                    "brings it back. Any number of sales can be parked at once.",
                "Checkout moves to the payment screen with the bill as it stands."
            )
        ),
        Block.Heading("Using a weighing scale"),
        Block.Para(
            "With a scale wired in and switched on in General Settings, the weight " +
                "arrives in the quantity box on its own as the item settles on the pan. " +
                "Type in that box and the scale stops overwriting you - the figure you " +
                "entered is kept. Use takes the field back for the scale."
        ),
        Block.Heading("Taking payment"),
        Block.Para(
            "Pick how the customer is paying and the panel below changes to suit it."
        ),
        Block.Bullets(
            listOf(
                "CASH - enter what was handed over and the change is worked out. With " +
                    "Cash Reception switched off it simply collects the exact amount.",
                "CREDIT - bills the sale to a customer's account. It needs a customer on " +
                    "file, and it is refused if their remaining credit will not carry the " +
                    "bill. The shortfall is named so the limit can be raised.",
                "CARD - for a sale taken on a card machine beside the till.",
                "ONLINE - shows a scan-to-pay code carrying the amount, so the customer " +
                    "confirms a figure rather than keying one in."
            )
        ),
        Block.Heading("Splitting one bill across two ways"),
        Block.Para(
            "Switch Split payment on under the Cash tile for a customer paying part in " +
                "notes and part by phone. Type the cash part and the UPI part fills " +
                "itself in; type the UPI part and the cash follows. The two always come " +
                "to the bill total, so there is nothing to work out."
        ),
        Block.Para(
            "The bill records both: the receipt prints a line per part with its own " +
                "amount, and the payment reports count each under its own mode."
        ),
        Block.Heading("Finishing"),
        Block.Para(
            "Complete Checkout saves the bill, prints it and returns to a fresh sale. " +
                "The bill can be reprinted afterwards from Bill History."
        )
    )

    private val RESTAURANT = listOf(
        Block.Para(
            "Which of the three ways of serving you see depends on App Settings > " +
                "Restaurant Mode. Switch off the ones the place does not do and they " +
                "disappear from the sale screen."
        ),
        Block.Heading("Dine In"),
        Block.Para(
            "Choose Table opens the floor plan, with sections as tabs and tables as " +
                "cards. Tap a free table to start an order on it; tap a busy one to open " +
                "the order already running there. Items are added exactly as on a " +
                "grocery sale."
        ),
        Block.Heading("Take Away and QSR"),
        Block.Para(
            "Neither has a table. A take-away is rung up at the counter and settled " +
                "there; QSR is quick service - rung up and paid in one go, with Hold to " +
                "park a ticket while the next customer is served."
        ),
        Block.Heading("The kitchen ticket"),
        Block.Para(
            "Print KOT sends the order to the kitchen. Only the items added since the " +
                "last ticket go on it, so a dish is never sent to be cooked twice. " +
                "Cancelled items print in their own section so the kitchen knows to stop."
        ),
        Block.Heading("Billing a table"),
        Block.Para(
            "Bill & Pay prints the bill and settles it. A table's bill is printed before " +
                "it is paid - the guest reads it, then hands over cash or a card - so " +
                "confirming payment does not print a second copy. A counter order " +
                "settles where it stands, without the checkout page."
        ),
        Block.Heading("Moving orders about"),
        Block.Bullets(
            listOf(
                "TRANSFER moves an order to a different table.",
                "MERGE puts two tables onto one bill.",
                "SPLIT divides one table's bill - a party paying separately."
            )
        ),
        Block.Para(
            "All three are dine-in only, and each can be switched off in App Settings if " +
                "the floor should not be doing it."
        )
    )

    private val MASTERS = listOf(
        Block.Para("Everything the till sells, charges and bills to is kept here."),
        Block.Heading("Products and what they need"),
        Block.Bullets(
            listOf(
                "CATEGORY/DEPARTMENT groups products into the sale grid's tabs.",
                "UNITS and RATE NAME are the lists the product form picks from, so both " +
                    "are filled in before products are added.",
                "PRODUCTS is the catalogue itself - name, code, barcode, category, unit, " +
                    "rate and tax."
            )
        ),
        Block.Para(
            "A product can carry more than one rate if Item Rate is set to Multiple in " +
                "General Settings. Name one of them MRP and the barcode label prints it " +
                "struck through beside what you actually charge."
        ),
        Block.Heading("Customers"),
        Block.Para(
            "Needed for credit sales, which have to be attributable. The credit limit on " +
                "the record is what decides how much a customer may owe; a sale that " +
                "would cross it is refused at the checkout with the shortfall named."
        ),
        Block.Heading("Extra charges"),
        Block.Para(
            "The shop's own additions - a service charge, a parcel charge - each as a " +
                "percentage or a flat amount, and each applied only to the order types " +
                "it names. A parcel charge can be set to reach take-away and QSR and " +
                "leave dine-in alone."
        ),
        Block.Heading("Barcodes and labels"),
        Block.Para(
            "The Barcode screen lists every product with its code. Tick the ones you " +
                "want and the barcode icon gives a code to any that have none - a real " +
                "EAN-13 in the range reserved for a shop's own use, so it can never " +
                "clash with a manufacturer's."
        ),
        Block.Para(
            "The printer icon on a row prints that product's shelf-edge labels and asks " +
                "how many. The label size opens on the roll you last used."
        ),
        Block.Caution(
            "Generating a code for a product that already has one replaces it, and every " +
                "label already on the shelf for that product stops scanning. The app asks " +
                "before doing it and can fill in the blanks only."
        ),
        Block.Heading("Restaurant masters"),
        Block.Para(
            "Restaurant mode adds WAITER, SECTION and TABLE - between them, the floor " +
                "plan the Choose Table screen draws."
        )
    )

    private val REPORTS_AND_BACKUP = listOf(
        Block.Heading("Reports"),
        Block.Para(
            "Each report takes a date range and can be printed or exported."
        ),
        Block.Bullets(
            listOf(
                "BILL-WISE - every bill over the period, with its tax and total.",
                "ITEM-WISE - what sold, how much of it, and for how much.",
                "PAYMENT-WISE - what came in by cash, card, UPI and credit.",
                "PERIOD, SHIFT and WAITER - the same takings cut by time, by who was on, " +
                    "and by who served."
            )
        ),
        Block.Para(
            "Bill History is separate from reports: it lists the bills themselves, and " +
                "is where one is reprinted, a duplicate taken, or a sale returned."
        ),
        Block.Heading("Backups"),
        Block.Bullets(
            listOf(
                "MANUAL BACKUP writes the whole database to a file you choose, now.",
                "AUTOMATIC BACKUP does the same on a timer while the till is open, and " +
                    "keeps copies for as long as the retention you set.",
                "RESTORE replaces everything on the till with a backup file."
            )
        ),
        Block.Caution(
            "A restore cannot be undone - it overwrites every bill, product and customer " +
                "currently on the till. Take a fresh backup first, even if you are about " +
                "to restore over it."
        ),
        Block.Para(
            "A backup is the only copy of the shop's books. If the device is lost or " +
                "wiped, what is in the backup file is what survives."
        )
    )

    private val TROUBLE = listOf(
        Block.Heading("Nothing prints"),
        Block.Para(
            "Check the printer is switched on and has paper, then open Print Settings > " +
                "Connections. Test Print on a row says whether the till can reach that " +
                "printer at all."
        ),
        Block.Para(
            "A printer set up under the wrong purpose prints nothing readable: receipts " +
                "go to BILL, kitchen tickets to KOT, labels to BARCODE. A label printer " +
                "and a receipt printer speak different languages, so neither can stand in " +
                "for the other."
        ),
        Block.Heading("The scanner does not find a product"),
        Block.Para(
            "The code on the packet has to be the code on the product. Master > Database " +
                "Settings > Barcode lists every product's code and can give one to any " +
                "that have none."
        ),
        Block.Heading("The weighing scale shows nothing"),
        Block.Para(
            "General Settings > Weighing Scale. The Port has to match how the scale is " +
                "connected - USB for an adapter, or the /dev/tty node for one wired to " +
                "the board - and the Baud Rate has to match the scale's own setting."
        ),
        Block.Para(
            "If a weight arrives but reads wrongly, it is the Starting Point and Ending " +
                "Point that are out: they say which characters of the scale's message " +
                "hold the figure. Decimal Position places the point when the scale sends " +
                "digits without one."
        ),
        Block.Heading("A label prints across two stickers"),
        Block.Para(
            "The size in the print popup has to match the roll loaded. Roll width is the " +
                "whole web the printer feeds, not one sticker - a 100mm roll carrying two " +
                "50mm stickers is 100, with Stickers across set to 2."
        ),
        Block.Para(
            "If the print drifts down the roll and across the gaps, the printer has not " +
                "sensed its labels. Run its own gap calibration - usually holding FEED " +
                "until it advances a few blank labels."
        ),
        Block.Heading("A user cannot sign in"),
        Block.Para(
            "The account may be blocked, or the user may be outside their shift. An admin " +
                "can check both under Master > User Management and Master > Database " +
                "Settings > Shifts."
        ),
        Block.Heading("The figures look wrong"),
        Block.Para(
            "Check Tax Settings first: whether prices include tax, and whether a discount " +
                "comes off before or after it, both change what a bill totals. A till set " +
                "up one way and reported on the other will not reconcile."
        )
    )

    /** The manual's chapters, in the order the tabs show them. */
    val ALL: List<Pair<String, List<Block>>>
        get() = listOf(
            "Getting Started" to GETTING_STARTED,
            "Selling & Payment" to SELLING,
            "Restaurant" to RESTAURANT,
            "Masters" to MASTERS,
            "Reports & Backup" to REPORTS_AND_BACKUP,
            "Troubleshooting" to TROUBLE
        )
}
