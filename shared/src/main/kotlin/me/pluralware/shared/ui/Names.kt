package me.pluralware.shared.ui

/** "A", "A and B", "A, B, and C". */
fun listOfNames(items: List<String>): String = when (items.size) {
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}
