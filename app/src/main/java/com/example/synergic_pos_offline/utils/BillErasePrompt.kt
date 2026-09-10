package com.example.synergic_pos_offline.utils

import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.database.UserDao

/**
 * Throwing the books away, asked for the same way wherever it is asked for.
 *
 * ## Why this is shared
 *
 * Three things can require the bills to go - the Erase Bills action in About, a
 * change to the Start Bill No., and a change to the Tax Mode - and each had grown its
 * own way of asking. About went through [BillErase] behind a warning, a password and
 * a backup; the two settings put up a two-line dialog and cleared the bills straight
 * afterwards.
 *
 * That was not a difference in manners. The bare clear takes the bills and leaves the
 * FLOOR standing - tables still open on orders whose kitchen tickets it has just
 * deleted, splits still split. So a restaurant that changed its tax mode was left
 * claiming it had no bills while half its room was mid-service against orders that no
 * longer existed. And nobody was asked for a password, and no backup was taken, for a
 * deletion every bit as final as the one in About that asks for both.
 *
 * The whole flow lives here now and all three routes run it: the warning that says
 * what is KEPT as well as what goes, the password, the backup, [BillErase.erase] -
 * which clears the floor with the bills - and the report of what actually went.
 */
object BillErasePrompt {

    /**
     * Asks whether [reason] is worth the bills, and erases them if it is.
     *
     * [reason] completes "...so the N bills already on this till" - it names what the
     * operator just did, because a warning that does not name the change is a warning
     * about nothing. [action] completes "the password for X to ..." and reads as a
     * thing being done: "change the tax mode".
     *
     * [onErased] runs on the main thread once the books are actually gone, so a caller
     * can save the setting that cost them - and only then, so a cancelled password or
     * a failed backup leaves the setting exactly as it was. [onCancelled] runs on
     * every other ending, so the screen can put itself back.
     *
     * EXACTLY ONE OF THE TWO ALWAYS RUNS. A caller has moved a control to get here and
     * that control is now showing something the till has not agreed to, so an ending
     * that reports neither leaves the screen stating a setting that is not in force -
     * see [eraseInBackground], where a failed backup used to do exactly that.
     *
     * [savesOwnTaxSettings] is true only from the Tax Mode change itself: erasing
     * resets Tax Settings to a fresh till's own default (see [BillErase.erase]), but
     * that caller's own [onErased] immediately saves the mode the operator actually
     * chose straight afterward, so the default is never the visible outcome there and
     * the warning does not claim it will be. The other two callers (Erase Bills, a
     * Start Bill No. change) leave the default standing, so they warn about it.
     *
     * A till with no bills never asks: there is nothing to lose.
     */
    fun confirm(
        fragment: Fragment,
        reason: String,
        action: String,
        onCancelled: () -> Unit = {},
        savesOwnTaxSettings: Boolean = false,
        onErased: () -> Unit
    ) {
        val context = fragment.context ?: return
        val preview = BillErase.preview(context)
        // ASKED ON CANCELLED BILLS TOO. This read preview.bills alone, which is the
        // live books only - so a till whose bills had all been cancelled counted as
        // having nothing to lose, skipped the warning, skipped the password and the
        // backup, and erased the archive without a word.
        if (!preview.hasAnything) {
            onErased()
            return
        }
        DialogUtils.showConfirm(
            context = context,
            title = "Erase existing bills?",
            message = warning(reason, preview, mentionsTaxReset = !savesOwnTaxSettings),
            positiveText = "Erase & Change",
            negativeText = "Cancel",
            destructive = true,
            onCancel = onCancelled
        ) {
            withPassword(fragment, action, onCancelled) {
                eraseInBackground(fragment, action, onCancelled, onErased)
            }
        }
    }

    /**
     * What the erase takes, and - the half nobody expects - what it leaves.
     *
     * Sale returns and credit recoveries are numbered from the same run as bills, so
     * the numbering only truly restarts when there are none of those either. Said
     * plainly rather than promising a fresh start that will not happen.
     */
    private fun warning(reason: String, preview: BillErase.Preview, mentionsTaxReset: Boolean = true): String {
        val counter = if (!preview.sharesCounter) {
            "\n\n- Numbering starts again from the Start No. in Bill Settings."
        } else {
            val kept = listOfNotNull(
                preview.saleReturns.takeIf { it > 0 }
                    ?.let { "$it sale return${if (it == 1) "" else "s"}" },
                preview.creditRecoveries.takeIf { it > 0 }
                    ?.let { "$it credit recover${if (it == 1) "y" else "ies"}" }
            ).joinToString(" and ")
            "\n\n- $kept are kept, and they are numbered from the same run as the " +
                "bills - so the next bill carries on from the highest of them rather " +
                "than starting again from your Start No."
        }
        val cancelled = if (preview.cancelled > 0) {
            " The ${preview.cancelled} cancelled bill(s) still archived on this till go with them."
        } else ""
        return "$reason, so the ${preview.bills} bill(s) already on this till can no " +
            "longer be reported or reprinted correctly. They will be thrown away, along " +
            "with their items, the payments taken against them, their print records and " +
            "their kitchen orders." + cancelled + " It cannot be undone." +
            "\n\nA backup is taken first, into Downloads/backup - everything but this " +
            "device's users and store registration, so restoring it later would not " +
            "disturb who can sign in." +
            counter +
            "\n\n- What customers owe is not written off. A credit sale's debt stays on " +
            "the ledger and on the customer, with the bill it came from gone." +
            "\n\n- Stock stays sold. The goods left the shop, so the quantities are not " +
            "put back." +
            "\n\n- The floor is cleared too. Tables still open lose their orders, any " +
            "split is undone, and every table goes back to Available - including any you " +
            "had blocked, which must be blocked again in the Table master." +
            (if (mentionsTaxReset) "\n\n- Tax Settings go back to a fresh till's own " +
                "default - MRP, tax on, any discount post-tax - whatever they were set " +
                "to before." else "") +
            "\n\nProducts, customers and every other setting" +
            (if (mentionsTaxReset) " besides Tax Settings, above," else "") +
            " are left as they are."
    }

    /**
     * Asks the signed-in operator for their own password before anything is destroyed.
     *
     * Fails CLOSED with nobody signed in. Not reachable through the app - every screen
     * that calls this is behind the login - but a deletion this final must refuse
     * rather than assume.
     */
    private fun withPassword(
        fragment: Fragment,
        action: String,
        onCancelled: () -> Unit,
        work: () -> Unit
    ) {
        val context = fragment.context ?: return
        val userId = SessionManager.currentUser?.userId
        if (userId.isNullOrBlank()) {
            DialogUtils.showSuccess(
                context = context,
                title = "Sign in first",
                message = "To $action the password of the operator using this till is " +
                    "needed, and nobody is signed in."
            )
            onCancelled()
            return
        }
        val users = UserDao(context)
        DialogUtils.showPasswordConfirm(
            context = context,
            title = "Confirm it's you",
            message = "Enter the password for $userId to $action.",
            positiveText = "Erase & Change",
            destructive = true,
            onCancel = onCancelled,
            verify = { typed -> users.verifyPassword(userId, typed) },
            onConfirmed = work
        )
    }

    /**
     * Backs the till up, erases, then reports - off the main thread, because both
     * halves touch every bill on the device.
     *
     * The backup is a PRECONDITION, not a courtesy: if it cannot be written, nothing
     * is erased and the setting that asked for it is left alone. A change that costs
     * the books should not also cost the only copy of them.
     *
     * EXACTLY ONE of [onCancelled] and [onErased] runs, on every ending.
     *
     * This is the whole contract, and it was broken here: the backup-failure path told
     * the operator that nothing had changed and then returned without telling the
     * CALLER. The Tax Settings screen was left with its radio sitting on the mode it
     * had just been refused - showing MRP on a till still trading Exclusive, and only
     * admitting it when the screen was closed and reopened. The screen cannot put
     * itself back if it is never told that nothing happened.
     */
    private fun eraseInBackground(
        fragment: Fragment,
        action: String,
        onCancelled: () -> Unit,
        onErased: () -> Unit
    ) {
        // The dialog needs a live screen to sit on. Without one nothing runs at all -
        // so the caller is told now, rather than waiting for a callback that cannot
        // come.
        if (fragment.context == null) {
            onCancelled()
            return
        }
        BusyDialog.run(fragment, "Backing up, then erasing bills...") {
            val context = fragment.context
            if (context == null) {
                BusyDialog.onMain(fragment) { onCancelled() }
                return@run
            }
            val backup = try {
                AutoBackup.backupBefore(context, action)
            } catch (e: Exception) {
                BusyDialog.onMain(fragment) {
                    // Told first, so the screen is already back on the setting still in
                    // force by the time the operator reads why.
                    onCancelled()
                    DialogUtils.showSuccess(
                        context = context,
                        title = "Nothing was changed",
                        message = "A full backup is taken before anything is erased, and " +
                            "this one could not be written: " +
                            (e.message ?: e.javaClass.simpleName) + "." +
                            "\n\nSo $action was not carried out. Check there is room on " +
                            "the device and try again."
                    )
                }
                return@run
            }
            // CAUGHT HERE, not left to BusyDialog's own catch.
            //
            // That catch shows "That did not finish" and returns - it has never heard
            // of [onCancelled], so a throw inside the erase reported nothing to the
            // caller at all. The Tax Mode change was left with its radio on a mode the
            // till had not taken and no way to know, which is how a foreign-key
            // exception in clearFloor turned into "the mode change does not work".
            val outcome = try {
                BillErase.erase(context)
            } catch (e: Exception) {
                android.util.Log.e("BillErase", "Erase failed part-way", e)
                BusyDialog.onMain(fragment) {
                    onCancelled()
                    DialogUtils.showSuccess(
                        context = context,
                        title = "Erase did not finish",
                        message = "The bills were backed up to $backup, but erasing them " +
                            "stopped part-way: " + (e.message ?: e.javaClass.simpleName) +
                            "." +
                            "\n\nSo $action was NOT carried out, and the till may be part " +
                            "way through the erase - check Bill History and the table " +
                            "floor before trading again."
                    )
                }
                return@run
            }
            BusyDialog.onMain(fragment) {
                // The setting is saved only now - after the books have actually gone.
                onErased()
                DialogUtils.showSuccess(
                    context = context,
                    title = "Bills erased",
                    message = "${outcome.bills} bill(s) erased" +
                        (if (outcome.cancelled > 0) ", and ${outcome.cancelled} cancelled bill(s) " +
                            "cleared from the archive" else "") +
                        ". The next bill will be numbered ${outcome.nextNumber}." +
                        floorNote(outcome) +
                        "\n\nThe till as it was is saved to $backup. It leaves out this " +
                        "device's users and store registration, so restoring it brings " +
                        "the bills back without changing who can sign in."
                )
            }
        }
    }

    /** Said only where there was a floor to clear, so a grocery till is not told
     *  about tables it does not have. */
    private fun floorNote(outcome: BillErase.Outcome): String {
        if (outcome.openTables == 0 && outcome.tablesFreed == 0) return ""
        val open = outcome.openTables.takeIf { it > 0 }
            ?.let { "$it open table order(s) cleared" }
        val freed = outcome.tablesFreed.takeIf { it > 0 }
            ?.let { "$it table(s) back to Available" }
        return "\n\n" + listOfNotNull(open, freed).joinToString(", ") + "."
    }
}
