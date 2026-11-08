package com.srisomesh.speed

/** A tiny MAC-prefix → vendor table for the common makers, so devices read as a brand, not just hex. */
object Oui {
    private val map = mapOf(
        "001A11" to "Google", "3C5AB4" to "Google", "F4F5E8" to "Google", "DA A1 19" to "Google",
        "F0:9F:C2" to "Ubiquiti", "FCECDA" to "Ubiquiti", "6845B6" to "TP-Link", "50C7BF" to "TP-Link",
        "AC84C6" to "TP-Link", "E894F6" to "TP-Link",
        "001C B3" to "Apple", "3C0754" to "Apple", "A4B197" to "Apple", "F0DBF8" to "Apple",
        "D0817A" to "Apple", "ACBC32" to "Apple", "6C4008" to "Apple", "F80377" to "Apple",
        "5CF938" to "Apple", "A85C2C" to "Apple", "DCA904" to "Apple",
        "001632" to "Samsung", "0023D7" to "Samsung", "5CF6DC" to "Samsung", "8425DB" to "Samsung",
        "C8A823" to "Samsung", "E8508B" to "Samsung", "F409D8" to "Samsung",
        "286C07" to "Xiaomi", "640980" to "Xiaomi", "7C1DD9" to "Xiaomi", "F8A45F" to "Xiaomi",
        "A4506B" to "Oppo", "C0EEFB" to "OnePlus", "94652D" to "OnePlus",
        "D46A6A" to "Vivo", "E0DCFF" to "Vivo", "8C0EE3" to "Vivo",
        "001DD8" to "Microsoft", "C83F26" to "Realtek", "000C29" to "VMware",
        "B827EB" to "Raspberry Pi", "DCA632" to "Raspberry Pi", "E45F01" to "Raspberry Pi",
        "D83ADD" to "Amazon", "FCA667" to "Amazon", "68544C" to "Amazon",
        "001788" to "Philips Hue", "ECFABC" to "Reliance Jio", "7899CD" to "Huawei", "48AD08" to "Huawei"
    ).mapKeys { it.key.replace(" ", "").replace(":", "").uppercase() }

    fun vendor(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        if (clean.length < 6) return null
        return map[clean.substring(0, 6)]
    }
}
