package com.llucs.samota.core.fus

data class FirmwareParts(
    val pda: String,
    val csc: String,
    val phone: String,
    val full: String
) {
    companion object {
        fun parse(input: String): FirmwareParts {
            val parts = input.split("/")
            return when (parts.size) {
                3 -> {
                    val pda = parts[0]
                    val csc = parts[1]
                    val phone = parts[2]
                    FirmwareParts(pda, csc, phone, "$pda/$csc/$phone/$pda")
                }
                4 -> {
                    val pda = parts[0]
                    val csc = parts[1]
                    val phone = parts[2]
                    FirmwareParts(pda, csc, phone, input)
                }
                else -> throw IllegalArgumentException("Firmware precisa ser PDA/CSC/PHONE ou PDA/CSC/PHONE/PDA")
            }
        }
    }
}
