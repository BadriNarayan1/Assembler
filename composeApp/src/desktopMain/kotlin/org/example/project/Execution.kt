package org.example.project

import java.math.BigInteger
import java.util.*

class Execution internal constructor() {
    var memory: HashMap<String, String> = HashMap()
    val registerFile: HashMap<String, String> = HashMap()

    fun addToMemory(address: String, value: String) {
        memory[address] = value
    }

    // data path
    var mdr: String? = null
        private set
    var pcMuxPc: String = "0x00000000"
        private set
    var pcTemp: String? = null
        private set
    var size: String? = null
        private set
    var ra: String? = null
        private set
    var rb: String? = null
        private set
    var immMuxInr: String? = null
        private set
    var rz: String? = null
        private set
    var mar: String? = null
        private set
    var ir: String? = null
        private set
    var rs1: String? = null
        private set
    var rs2: String? = null
        private set
    var rd: String? = null
        private set
    val valueRegister: String? = null
    var clock: Int? = null
        private set
    var immMuxB: String? = null
        private set
    var rm: String? = null
        private set
    var ry: String? = null
        private set
    private var textSegment: HashMap<String, String>? = null


    fun setTextSegment(textSegment: HashMap<String, String>?) {
        this.textSegment = textSegment
    }

    //Control Path
    val muxMdr: Boolean? = null
    var muxMa: Boolean? = null
        private set
    var muxB: Boolean? = null
        private set
    var muxY: Int? = null
        private set
    var muxInr: Boolean? = null
        private set
    var muxPc: Boolean? = null
        private set
    private var branch: Boolean? = null
    private var condition: Boolean? = null
    var memRead: Boolean? = null
        private set
    var aluOp: String? = null
        private set
    var memWrite: Boolean? = null
        private set
    var regWrite: Boolean? = null
        private set

    init {
        for (i in 0..31) {
            val address = "x$i"
            registerFile[address] = "0x00000000"
        }
        registerFile["x2"] = "0x7FFFFFDC"
    }


    fun fetch() {
        if (textSegment == null) {
            println("Error: Text Segment is not initialized!")
            return
        }

        // Fetch the instruction from memory using PC
        val pcHex = pcMuxPc // Get current PC value
        ir = textSegment!!.getOrDefault(pcHex, "0x00000000") // Default to NOP if no instruction found


        // Convert PC from hex to integer, increment by 4, and store in pcTemp
        var pcValue = pcHex.substring(2).toInt(16) // Convert hex string to integer
        pcValue += 4 // Increment PC by 4 (32-bit instruction size)
        pcTemp = "0x" + String.format("%08X", pcValue) // Convert back to hex string
        pcMuxPc = pcTemp!!

        // Update the clock cycle
        if (clock == null) {
            clock = 0
        }

        // Debugging output
        println("Fetch Stage:")
        println("PC: $pcHex")
        println("Instruction Register (IR): $ir")
        println("Updated PC: $pcTemp")
        println("Clock Cycle: $clock")
    }


    fun decode() {
        if (ir == null || ir!!.length != 10) { // Ensure IR is valid (0xXXXXXXXX format)
            println("Error: Invalid instruction in IR")
            return
        }

        var func3: String? = null
        var func7: String? = null
        // Convert hex instruction to binary (excluding "0x" prefix)
        val binary = String.format(
            "%32s", Integer.toBinaryString(
                Integer.parseUnsignedInt(
                    ir!!.substring(2), 16
                )
            )
        ).replace(' ', '0')

        // Extract opcode (bits 0-6)
        val opcode = binary.substring(25, 32)

        // Decode based on opcode
        when (opcode) {
            "0110011" -> {
                // R-Type (add, sub, and, or, sll, slt, sra, srl, xor, mul, div, rem)
                rd = binary.substring(20, 25)
                rs1 = binary.substring(12, 17)
                rs2 = binary.substring(7, 12)
                func3 = binary.substring(17, 20)
                func7 = binary.substring(0, 7)

                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")
                rb = registerFile.getOrDefault("x" + rs2!!.toInt(2), "0x00000000")

                // ALU operation lookup
                aluOp = if (func3 == "000") {
                    if (func7 == "0000000") "ADD" else if (func7 == "0100000") "SUB" else if (func7 == "0000001") "MUL" else "INVALID"
                } else if (func3 == "111") {
                    "AND"
                } else if (func3 == "110") {
                    if (func7 == "0000001") "REM" else "OR"
                } else if (func3 == "001") {
                    "SLL"
                } else if (func3 == "010") {
                    "SLT"
                } else if (func3 == "101") {
                    if (func7 == "0000000") "SRL" else if (func7 == "0100000") "SRA" else "INVALID"
                } else if (func3 == "100") {
                    if (func7 == "0000000") "XOR" else if (func7 == "0000001") "DIV" else "INVALID"
                } else {
                    throw IllegalArgumentException("Invalid R-type instruction")
                }
                muxPc = false
                muxInr = false
                muxMa = false // selecting pc
                muxY = 0
                branch = false
                memRead = false
                memWrite = false
                regWrite = true
                muxB = false
            }

            "0010011" -> {
                // I-Type (addi, andi, ori)
                rd = binary.substring(20, 25)
                rs1 = binary.substring(12, 17)
                func3 = binary.substring(17, 20)
                immMuxB = binary.substring(0, 12)

                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")

                // Sign-extend immMuxB to 32-bit
                var immValue = immMuxB!!.toInt(2)
                if (immMuxB!![0] == '1') { // Negative number (sign extend)
                    immValue = immValue or -0x1000
                }
                immMuxB = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // ALU operation lookup
                aluOp = when (func3) {
                    "000" -> "ADD"
                    "111" -> "AND"
                    "110" -> "OR"
                    else -> throw IllegalArgumentException("Invalid I-type instruction")
                }
                muxPc = false
                muxInr = false
                muxMa = false // selecting pc
                muxY = 0
                branch = false
                memRead = false
                memWrite = false
                regWrite = true
                muxB = true
            }

            "0000011" -> {
                // Load (lb, lh, lw, ld)
                rd = binary.substring(20, 25)
                rs1 = binary.substring(12, 17)
                func3 = binary.substring(17, 20)
                immMuxB = binary.substring(0, 12)
                // Fetch rs1 value from the register file
                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")

                // Extract and sign-extend the 12-bit immediate
                var immValue = immMuxB!!.toInt(2)
                if (immMuxB!![0] == '1') { // If negative, sign-extend
                    immValue = immValue or -0x1000
                }
                immMuxB = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Determine memory access size based on func3
                 size = when (func3) {
                    "000" -> "BYTE"
                    "001" -> "HALF"
                    "010" -> "WORD"
                    "011" -> "DOUBLE"
                    else -> throw IllegalArgumentException("Invalid load instruction")
                }

                // Set ALU operation to perform address computation (ra + imm)
                aluOp = "LOAD"

                // Control signals
                muxPc = false
                muxInr = false
                muxMa = true // Selecting rz
                muxY = 1
                branch = false
                memRead = true
                memWrite = false
                regWrite = true
                muxB = true
            }

            "1100111" -> {
                // JALR
                rd = binary.substring(20, 25)
                rs1 = binary.substring(12, 17)
                func3 = binary.substring(17, 20)
                immMuxInr = binary.substring(0, 12)
                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")

                // Sign-extend immMuxB to 32-bit
                var jalrImm = immMuxInr!!.toInt(2)
                if (immMuxInr!![0] == '1') {
                    jalrImm = jalrImm or -0x1000
                }
                immMuxInr = String.format("%32s", Integer.toBinaryString(jalrImm)).replace(' ', '0')

                aluOp = "JALR"
                muxPc = true // selecting ra
                muxInr = false
                muxMa = false // selecting pc
                muxY = 2
                branch = true
                memRead = false
                memWrite = false
                regWrite = true
                muxB = null
            }

            "0100011" -> {
                // S-Type (sb, sw, sd, sh)
                rs1 = binary.substring(12, 17)
                rs2 = binary.substring(7, 12)
                func3 = binary.substring(17, 20)
                immMuxB = binary.substring(0, 7) + binary.substring(20, 25) // Immediate field
                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")
                rb = registerFile.getOrDefault("x" + rs2!!.toInt(2), "0x00000000")
                rm = rb

                // Sign-extend immMuxB to 32-bit
                var immValue = immMuxB!!.toInt(2)
                if (immMuxB!![0] == '1') { // Negative number (sign extend)
                    immValue = immValue or -0x1000
                }
                immMuxB = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Determine size based on func3
                size = when (func3) {
                    "000" -> "BYTE"
                    "001" -> "HALF"
                    "010" -> "WORD"
                    "011" -> "DOUBLE"
                    else -> throw IllegalArgumentException("Invalid S-type instruction")
                }

                aluOp = "STORE"
                // Control signals
                muxPc = false
                muxInr = false
                muxMa = true // selecting rz
                muxY = null
                branch = false
                memRead = false
                memWrite = true
                regWrite = false
                muxB = true
            }

            "1100011" -> {
                // SB-Type (beq, bne, bge, blt)
                rs1 = binary.substring(12, 17)
                rs2 = binary.substring(7, 12)
                func3 = binary.substring(17, 20)
                ra = registerFile.getOrDefault("x" + rs1!!.toInt(2), "0x00000000")
                rb = registerFile.getOrDefault("x" + rs2!!.toInt(2), "0x00000000")

                // Extract branch immediate & sign-extend it
                val immRaw =
                    binary.substring(0, 1) + binary.substring(24, 25) + binary.substring(1, 7) + binary.substring(
                        20,
                        24
                    ) + "0"
                var immValue = immRaw.toInt(2)
                if (immRaw[0] == '1') { // Negative number (sign extend)
                    immValue = immValue or -0x1000
                }
                immMuxInr = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Set ALU operation based on func3
                aluOp = when (func3) {
                    "000" -> "BEQ"
                    "001" -> "BNE"
                    "100" -> "BLT"
                    "101" -> "BGE"
                    else -> throw IllegalArgumentException("Invalid SB-type instruction")
                }

                // Control signals
                muxPc = false
                muxInr = false
                muxMa = false // selecting pc
                muxY = null
                branch = true
                memRead = false
                memWrite = false
                regWrite = false
                muxB = false
            }

            "0110111" -> {
                // U-Type (LUI)
                rd = binary.substring(20, 25)

                // Extract 20-bit immediate and shift left by 12 (LUI semantics)
                var immValue = binary.substring(0, 20).toInt(2) shl 12

                // Sign-extend to 32-bit
                if (binary[0] == '1') { // If MSB is 1 (negative number)
                    immValue = immValue or -0x100000 // Extend sign
                }

                immMuxB = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Set ALU operation
                aluOp = "LUI"

                // Control signals
                muxPc = false
                muxInr = false
                muxMa = false // selecting PC
                muxY = 0
                branch = false
                memRead = false
                memWrite = false
                regWrite = true
                muxB = true
            }

            "0010111" -> {
                // U-Type (AUIPC)
                rd = binary.substring(20, 25)
                ra = pcMuxPc
                // Extract 20-bit immediate and shift left by 12 (AUIPC semantics)
                var immValue = binary.substring(0, 20).toInt(2) shl 12

                // Sign-extend to 32-bit
                if (binary[0] == '1') { // If MSB is 1 (negative number)
                    immValue = immValue or -0x100000 // Extend sign
                }

                immMuxB = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Set ALU operation
                aluOp = "AUIPC"

                // Control signals
                muxPc = false
                muxInr = false
                muxMa = false // selecting PC
                muxY = 0
                branch = false
                memRead = false
                memWrite = false
                regWrite = true
                muxB = true
            }

            "1101111" -> {
                // UJ-Type (JAL)
                rd = binary.substring(20, 25)

                // Extract 20-bit JAL immediate and rearrange the bits correctly
                val immBinary =
                    binary[0].toString() + binary.substring(12, 20) + binary.substring(11, 12) + binary.substring(
                        1,
                        11
                    ) + "0"

                // Convert to signed integer
                var immValue = immBinary.toInt(2)

                // Sign-extend to 32-bit
                if (binary[0] == '1') { // If MSB is 1 (negative number)
                    immValue = immValue or -0x200000 // Extend sign
                }

                immMuxInr = String.format("%32s", Integer.toBinaryString(immValue)).replace(' ', '0')

                // Set ALU operation for JAL
                aluOp = "JAL"

                // Control signals
                muxPc = false
                muxInr = false
                muxMa = false // selecting PC
                muxY = 2
                branch = true
                memRead = false
                memWrite = false
                regWrite = true
                muxB = null
            }

            else -> {
                println("Error: Unsupported opcode $opcode")
                return
            }
        }

        // Debug output
        println("Decode Stage:")
        println("Opcode: $opcode")
        println("rd: $rd")
        println("rs1: $rs1")
        println("rs2: $rs2")
        println("func3: $func3")
        println("func7: $func7")
        println("Immediate: $immMuxB")
    }

    fun execute() {
        if (aluOp == null) {
            println("Error: ALU operation not set.")
            return
        }

        // Convert ra and rb from hex to int, handling null cases
        val op1 = if (ra != null) Integer.parseUnsignedInt(ra!!.substring(2), 16) else 0
        var op2 = 0

        // Choose operand based on instruction type (rb may be null for I-type and store instructions)
        if (muxB != null && muxB as Boolean) {
            op2 = immMuxB?.let { BigInteger(it, 2).toInt() } ?: 0
        } else if (rb != null) {
            op2 = Integer.parseUnsignedInt(rb!!.substring(2), 16) // Register value for R-type & SB-type
        }

        var result = 0

        // Execute ALU operation
        when (aluOp) {
            "ADD" -> result = op1 + op2
            "SUB" -> result = op1 - op2
            "MUL" -> result = op1 * op2
            "DIV" -> result = if (op2 != 0) op1 / op2 else 0
            "REM" -> result = if (op2 != 0) op1 % op2 else 0
            "AND" -> result = op1 and op2
            "OR" -> result = op1 or op2
            "XOR" -> result = op1 xor op2
            "SLL" -> result = op1 shl (op2 and 0x1F)
            "SRL" -> result = op1 ushr (op2 and 0x1F)
            "SRA" -> result = op1 shr (op2 and 0x1F)
            "SLT" -> result = if (op1 < op2) 1 else 0
            "LUI" -> result = op2
            "AUIPC" -> result = op1 + op2
            "JAL" -> condition = true
            "JALR" -> condition = true
            "BEQ" -> condition = (op1 == op2)
            "BNE" -> condition = (op1 != op2)
            "BLT" -> condition = (op1 < op2)
            "BGE" -> condition = (op1 >= op2)
            "LOAD" -> result = op1 + op2
            "STORE" -> result = op1 + op2
            else -> {
                println("Error: Unsupported ALU operation $aluOp")
                return
            }
        }

        // Store result in rz unless it's a branch instruction
        if (!aluOp!!.startsWith("B")) {
            rz = "0x" + String.format("%08X", result)
        }

        // Handle branch condition (update PC if branch is taken)
        if (branch != null && branch as Boolean) {
            if (condition!!) {
                muxInr = true
            }
        }

        if (aluOp === "LOAD" || aluOp === "STORE") {
            mdr = rm
            mar = rz
        } else {
            mar = null
        }

        // Debug output
        println("Execute Stage:")
        println("ALU Operation: $aluOp")
        println("Operand 1 (ra): " + (if (ra != null) ra else "NULL"))
        println("Operand 2 (rb/imm): " + (if (muxB != null && muxB as Boolean) immMuxB else (if (rb != null) rb else "NULL")))
        println("Result (rz): $rz")
        if (branch != null && branch as Boolean) {
            println("Branch Taken: $condition")
        }
    }

    fun memoryAccess() {
        if (mar == null) {
            if (muxInr!!) {
                var pcValue = if (muxPc!!) {
                    ra!!.substring(2).toInt(16)
                } else {
                    pcMuxPc.substring(2).toInt(16)
                }
                if (aluOp != "JALR") {
                    pcValue = pcValue + BigInteger(immMuxInr, 2).toInt() - 4
                }
                else {
                    pcValue = pcValue + BigInteger(immMuxInr, 2).toInt()
                }
                pcMuxPc = String.format("0x%08X", pcValue)
            }
        } else {
            // Convert MAR to integer memory address
            val address = Integer.parseUnsignedInt(mar!!.substring(2), 16)

            if (memRead != null && memRead as Boolean) { // Load instruction
                val loadedValue = StringBuilder()

                // Read memory byte-by-byte
                when (size) {
                    "BYTE" -> loadedValue.append(memory.getOrDefault(String.format("0x%08X", address), "00"))
                    "HALF" -> {
                        var i = 0
                        while (i < 2) {
                            // Read 2 bytes
                            loadedValue.insert(0, memory.getOrDefault(String.format("0x%08X", address + i), "00"))
                            i++
                        }
                    }

                    "WORD" -> {
                        var i = 0
                        while (i < 4) {
                            // Read 4 bytes
                            loadedValue.insert(0, memory.getOrDefault(String.format("0x%08X", address + i), "00"))
                            i++
                        }
                    }

                    "DOUBLE" -> {
                        var i = 0
                        while (i < 8) {
                            // Read 8 bytes
                            loadedValue.insert(0, memory.getOrDefault(String.format("0x%08X", address + i), "00"))
                            i++
                        }
                    }

                    else -> {
                        println("Error: Invalid memory size for load.")
                        return
                    }
                }

                mdr = "0x" + loadedValue.toString().uppercase(Locale.getDefault()) // Store value in MDR
                println("Loaded Value (MDR): $mdr from Address (MAR): $mar")
            }

            if (memWrite != null && memWrite as Boolean) { // Store instruction
                if (mdr == null) {
                    println("Error: MDR (Memory Data Register) is null.")
                    return
                }

                val value = mdr!!.substring(2) // Remove "0x" prefix

                // Store memory byte-by-byte
                when (size) {
                    "BYTE" -> memory[String.format("0x%08X", address)] =
                        value.substring(value.length - 2) // Last 2 chars
                    "HALF" -> {
                        var i = 0
                        while (i < 2) {
                            // Store 2 bytes
                            memory[String.format("0x%08X", address + i)] =
                                value.substring(value.length - (2 * (i + 1)), value.length - (2 * i))
                            i++
                        }
                    }

                    "WORD" -> {
                        var i = 0
                        while (i < 4) {
                            // Store 4 bytes
                            memory[String.format("0x%08X", address + i)] =
                                value.substring(value.length - (2 * (i + 1)), value.length - (2 * i))
                            i++
                        }
                    }

                    "DOUBLE" -> {
                        var i = 0
                        while (i < 8) {
                            // Store 8 bytes
                            memory[String.format("0x%08X", address + i)] =
                                value.substring(value.length - (2 * (i + 1)), value.length - (2 * i))
                            i++
                        }
                    }

                    else -> {
                        println("Error: Invalid memory size for store.")
                        return
                    }
                }
                println("Stored Value (MDR): $mdr to Address (MAR): $mar")
            }
        }
        if (muxY == 0) {
            ry = rz
        } else if (muxY == 1) {
            ry = mdr
        } else if (muxY == 2) {
            ry = pcTemp
        }
    }

    fun writeBack() {
        if (regWrite == null || !regWrite!!) {
            println("Skipping WriteBack: regWrite is disabled.")
            clock = clock!! + 1
            return
        }

        if (rd == null || rd == "00000") { // x0 should not be modified
            println("Skipping WriteBack: Destination register is x0.")
            clock = clock!! + 1
            return
        }


        val registerName = "x" + rd!!.toInt(2)
        if (ry != null) {
            registerFile[registerName] = ry!!
        }
        clock = clock!! + 1
        println("WriteBack: Register $registerName updated with $ry")
    }
} //    public void completeExecution() {
//
//    }

