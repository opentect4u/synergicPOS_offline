package com.example.synergic_pos_offline.utils

import java.util.Calendar

/**
 * The Terms & Conditions, Privacy Policy and Copyright notice, in one place.
 *
 * ## ⚠ The text below is a draft, not legal advice
 *
 * It was written to give the screens something complete and plausible to show, and to
 * cover what this app actually does - it stores its data on the device, it talks to a
 * registration server, it is licensed per device rather than sold. **It has not been
 * reviewed by anybody qualified to review it.** Before this ships to a paying shop,
 * the company's own terms should replace what is here.
 *
 * That replacement is meant to be easy, which is why all three documents are in this
 * one file and nothing else in the app carries a word of them: change the three
 * constants and every screen that shows them follows - the registration agreement,
 * About App, and anything added later.
 *
 * ## Editing them
 *
 * Plain text, one blank line between paragraphs. A line beginning with "##" is drawn
 * as a heading; everything else is a paragraph. Keep [VERSION] moving when the terms
 * change in substance - it is what an accepted agreement is recorded against, so a
 * shop that agreed to the old terms can be told the new ones need agreeing to.
 */
object LegalDocuments {

    /**
     * Which edition of the terms these are.
     *
     * Stored alongside an operator's acceptance, so a change here is a change a shop
     * is asked to agree to again rather than one that quietly rewrites what they
     * already signed up to. Date-shaped so it sorts and reads as what it is.
     */
    const val VERSION = "2026-08-18"

    /** What the app calls itself in its own legal text. */
    private const val PRODUCT = "Synergic POS"

    /** Who publishes it. Replace along with the text below. */
    private const val PUBLISHER = "Opentect4u"

    /** The year the notice runs from; the current year closes the range. */
    private const val COPYRIGHT_FROM = 2024

    val TERMS: String = """
        ## 1. What this agreement covers

        These terms govern your use of $PRODUCT ("the software") on this device. By
        registering the device you accept them. If you do not accept them, do not
        register the device and do not use the software.

        ## 2. Licence

        The software is licensed to you, not sold. The licence is granted for the
        registered device and for the period stated at registration, and it is
        non-exclusive and non-transferable. You may not copy, resell, sub-license,
        rent out or redistribute the software, and you may not attempt to derive its
        source code from the software as supplied.

        ## 3. Registration and renewal

        A device must be registered and verified before it can be used for billing.
        The registration runs until the renewal date shown in About App. When it
        lapses the software may stop accepting new sales until it is renewed. Your
        data remains on the device and is not deleted by a lapse.

        ## 4. Your data is yours

        Sales, products, customers and settings are stored on this device and belong
        to you. $PUBLISHER does not claim ownership of them. You are responsible for
        keeping backups; the software provides a backup facility for that purpose,
        and using it is your decision and your responsibility.

        ## 5. Your obligations

        You are responsible for the accuracy of what you enter, for the bills and tax
        figures the software produces from it, and for meeting the tax and record-
        keeping duties that apply to your business. You are responsible for keeping
        login credentials secure and for what is done under them on this device.

        ## 6. Availability

        The software runs offline by design. Registration, verification and any other
        server-backed feature need a working connection and are not guaranteed to be
        available at all times.

        ## 7. Limitation of liability

        The software is provided "as is". To the fullest extent the law allows,
        $PUBLISHER is not liable for lost profits, lost data, business interruption
        or other indirect or consequential loss arising from its use. Nothing here
        limits liability that cannot be limited by law.

        ## 8. Ending the agreement

        You may stop using the software at any time. $PUBLISHER may end this licence
        if these terms are breached. Your data stays on your device either way.

        ## 9. Changes

        These terms may be updated. A substantive change is presented for acceptance
        before the software continues to be used.

        ## 10. Governing law

        This agreement is governed by the laws of India, and the courts of India have
        jurisdiction over any dispute arising from it.
    """.trimIndent()

    val PRIVACY: String = """
        ## What this app stores, and where

        $PRODUCT is an offline till. Your sales, bills, products, customers, stock and
        settings are stored in a database on this device. They are not uploaded to
        $PUBLISHER, and they are not shared with any third party by the software.

        ## What leaves the device

        Only what registration and verification need: the store details you enter when
        registering, and an identifier for this device. These are sent to $PUBLISHER's
        registration service so the device can be licensed and verified.

        ## Backups

        Backups are written to the device's own Downloads folder, at your request or
        on the schedule you set. Where those files then go is under your control -
        anything you copy off the device travels under your own arrangements.

        ## Printing

        Slips are sent to the printers you configure, over Bluetooth, USB or your own
        network. Nothing is routed through $PUBLISHER to reach a printer.

        ## Permissions

        The app asks only for what it uses: network access for registration and
        verification, and Bluetooth and storage for printing and backups. It does not
        collect location, contacts or usage analytics.

        ## Customer information you hold

        Where you record a customer's name, phone number, address or GSTIN, you are
        the one holding that information and you are responsible for handling it
        lawfully. The software stores it on your device and does nothing else with it.

        ## Contact

        For any question about this policy, contact $PUBLISHER through the channel
        your registration was arranged by.
    """.trimIndent()

    val COPYRIGHT: String
        get() {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val span = if (year > COPYRIGHT_FROM) "$COPYRIGHT_FROM-$year" else "$COPYRIGHT_FROM"
            return """
                © $span $PUBLISHER. All rights reserved.

                $PRODUCT, its source code, its screens and its printed formats are the
                property of $PUBLISHER and are protected by copyright. The software is
                licensed for use on registered devices; it is not sold, and no right
                to copy, adapt or redistribute it is granted by that licence.

                ## Third-party components

                This app is built on open-source software, used under the licences its
                authors granted:

                AndroidX and Material Components for Android — Apache License 2.0,
                © The Android Open Source Project.

                Kotlin standard library — Apache License 2.0, © JetBrains s.r.o.

                bcrypt (at.favre.lib) — Apache License 2.0, © Patrick Favre-Bulle.
                Used to hash the passwords stored on this device.

                Roboto Mono — Apache License 2.0, © Google. The typeface bills are
                printed in.

                The thermal printer SDK bundled with this app is supplied by its
                vendor and used under the terms granted with it.

                Each licence is reproduced in full in the documentation supplied with
                your registration.
            """.trimIndent()
        }

    /** The three documents, in the order About App lists them. */
    val ALL: List<Pair<String, String>>
        get() = listOf(
            "Terms & Conditions" to TERMS,
            "Privacy Policy" to PRIVACY,
            "Copyright Information" to COPYRIGHT
        )
}
