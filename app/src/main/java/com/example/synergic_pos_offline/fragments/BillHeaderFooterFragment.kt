package com.example.synergic_pos_offline.fragments

/**
 * "Bill Header & Footer" management screen — a concrete [DataTableFragment].
 *
 * The table shows 2 columns for readability, while the Add/Edit popup collects
 * 6 fields (the extra values are stored on each row).
 */
class BillHeaderFooterFragment : DataTableFragment() {

    override val screenTitle = "Bill Header & Footer"

    // Columns displayed in the table.
    override val columns = listOf("Name", "Type")

    // Fields shown in the Add/Edit popup form (6 fields).
    override val formFields = listOf(
        "Name", "Type", "Address", "Phone", "GST No", "Footer Note"
    )

    override fun loadRows(): MutableList<DataRow> = mutableListOf(
        DataRow("1", listOf("Main Store Header", "Header", "12 MG Road, Kolkata", "9830012345", "19ABCDE1234F1Z5", "Thank you, visit again")),
        DataRow("2", listOf("Thank You Footer", "Footer", "12 MG Road, Kolkata", "9830012345", "19ABCDE1234F1Z5", "Goods once sold not returned")),
        DataRow("3", listOf("GST Details Header", "Header", "45 Park Street, Kolkata", "9830067890", "19ABCDE1234F1Z5", "GST included in price")),
        DataRow("4", listOf("Return Policy Footer", "Footer", "45 Park Street, Kolkata", "9830067890", "19ABCDE1234F1Z5", "7 day return policy"))
    )
}
