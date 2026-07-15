package com.example.synergic_pos_offline.fragments

/**
 * "Bill Header & Footer" management screen — a concrete [DataTableFragment].
 * Demonstrates how any list screen is built: just declare columns + rows.
 */
class BillHeaderFooterFragment : DataTableFragment() {

    override val screenTitle = "Bill Header & Footer"

    override val columns = listOf("Name", "Type")

    override fun loadRows(): MutableList<DataRow> = mutableListOf(
        DataRow("1", listOf("Main Store Header", "Header")),
        DataRow("2", listOf("Thank You Footer", "Footer")),
        DataRow("3", listOf("GST Details Header", "Header")),
        DataRow("4", listOf("Return Policy Footer", "Footer")),
        DataRow("5", listOf("Branch Address Header", "Header"))
    )
}
