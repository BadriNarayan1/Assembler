package org.example.project

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

import java.math.BigInteger

class ExecutionViewModel {
    val execution = Execution()
    val currentStage = mutableStateOf(PipelineStage.FETCH)
    val textSegment = mutableStateMapOf<String, String>()
    val memoryMap = mutableStateMapOf<String, String>()
    val registerFile = mutableStateMapOf<String, String>()
    init {
        registerFile.putAll(execution.registerFile)
    }

    //  Data Path - Reactive State Variables
    val mdr = mutableStateOf<String?>(null)
    val pcMuxPc = mutableStateOf("0x00000000")
    val pcTemp = mutableStateOf<String?>(null)
    val size = mutableStateOf<String?>(null)
    val ra = mutableStateOf<String?>(null)
    val rb = mutableStateOf<String?>(null)
    val immMuxInr = mutableStateOf<String?>(null)
    val rz = mutableStateOf<String?>(null)
    val mar = mutableStateOf<String?>(null)
    val ir = mutableStateOf<String?>(null)
    val rs1 = mutableStateOf<String?>(null)
    val rs2 = mutableStateOf<String?>(null)
    val rd = mutableStateOf<String?>(null)
    val valueRegister = mutableStateOf<String?>(null)
    val clock = mutableStateOf<Int?>(null)
    val immMuxB = mutableStateOf<String?>(null)
    val rm = mutableStateOf<String?>(null)
    val ry = mutableStateOf<String?>(null)

    //  Control Path - Reactive Booleans / Integers / String
    val muxMdr = mutableStateOf<Boolean?>(null)
    val muxMa = mutableStateOf<Boolean?>(null)
    val muxB = mutableStateOf<Boolean?>(null)
    val muxY = mutableStateOf<Int?>(null)
    val muxInr = mutableStateOf<Boolean?>(null)
    val muxPc = mutableStateOf<Boolean?>(null)
    val branch = mutableStateOf<Boolean?>(null)
    val condition = mutableStateOf<Boolean?>(null)
    val memRead = mutableStateOf<Boolean?>(null)
    val aluOp = mutableStateOf<String?>(null)
    val memWrite = mutableStateOf<Boolean?>(null)
    val regWrite = mutableStateOf<Boolean?>(null)



    fun parseMachineCode(output: String) {
        textSegment.clear()
        memoryMap.clear()
        val lines = output.lines()
        var isMemorySection = false

        for (line in lines) {
            val parts = line.trim().split(" ", limit = 3)
            if (parts.size >= 2) {
                var pc = parts[0]
                val instruction = parts[1]

                // Detect end of instructions section
                if (instruction.equals("0xdeadbeef", ignoreCase = true)) {
                    val pcInt = pc.removePrefix("0x").toUIntOrNull(16)
                    pc = if (pcInt != null) "0x" + pcInt.toString(16).padStart(8, '0').uppercase() else pc
                    textSegment[pc] = instruction
                    isMemorySection = true
                    continue
                }

                if (!isMemorySection) {
                    // Add to PC -> Instruction map
                    if (pc.startsWith("0x")) {
                        val pcInt = pc.removePrefix("0x").toUIntOrNull(16)
                        pc = if (pcInt != null) "0x" + pcInt.toString(16).padStart(8, '0').uppercase() else pc
                        textSegment[pc] = instruction.uppercase()

                        // Now add the instruction bytes to memoryMap
                        val instrHex = instruction.removePrefix("0x").padStart(8, '0') // 32-bit
                        for (i in 0 until 4) {
                            val byteHex = instrHex.substring((6 - i * 2), (8 - i * 2)) // Little Endian
                            val byteAddress = "0x" + (pcInt!! + i.toUInt()).toString(16).padStart(8, '0').uppercase()
                            memoryMap[byteAddress] = byteHex
                        }
                    }
                } else {
                    // Add to Memory map (ensure the address looks like memory)
                    if (pc.startsWith("0x") && instruction.startsWith("0x")) {
                        memoryMap["0x" + pc.removePrefix("0x").uppercase()] = instruction.removePrefix("0x")
                    }
                }
            }
        }

        execution.setTextSegment(HashMap(textSegment))
        execution.memory = HashMap(memoryMap)
        println("Parsing done")
    }



    fun fetch() {
        execution.fetch() // Call the Java Execution's fetch()

        // Update the Compose observable states from Execution's getters
        ir.value = execution.ir
        pcTemp.value = execution.pcTemp
        pcMuxPc.value = execution.pcMuxPc
        clock.value = execution.clock

        // Optional debug output
        println("ViewModel Fetch Updated:")
        println("IR: ${ir.value}")
        println("PC Temp: ${pcTemp.value}")
        println("PC Mux PC: ${pcMuxPc.value}")
        println("Clock: ${clock.value}")
    }


    fun decode() {
        execution.decode() // Call the Java decode logic

        // Update observables from Execution's getters
        ir.value = execution.ir
        rs1.value = execution.rs1
        rs2.value = execution.rs2
        rd.value = execution.rd
        ra.value = execution.ra
        rb.value = execution.rb
        immMuxB.value = execution.immMuxB
        aluOp.value = execution.aluOp
        immMuxInr.value = execution.immMuxInr?.let {
            val immValue = if (aluOp.value != "JALR") {
                BigInteger(it, 2).toInt() - 4
            } else {
                BigInteger(it, 2).toInt()
            }
            String.format("%32s", Integer.toBinaryString(immValue and 0xFFFFFFFF.toInt())).replace(' ', '0')
        }
        size.value = execution.size
        rm.value = execution.rm

        muxMdr.value = execution.muxMdr
        muxMa.value = execution.muxMa
        muxB.value = execution.muxB
        muxY.value = execution.muxY
        muxInr.value = execution.muxInr
        muxPc.value = execution.muxPc
        memRead.value = execution.memRead
        memWrite.value = execution.memWrite
        regWrite.value = execution.regWrite

        // Optional debug
        println("ViewModel Decode Updated:")
        println("rs1: ${rs1.value}, rs2: ${rs2.value}, rd: ${rd.value}")
        println("ra: ${ra.value}, rb: ${rb.value}")
        println("aluOp: ${aluOp.value}, immMuxB: ${immMuxB.value}")
    }


    fun execute() {
        execution.execute()  // Call the Java execute logic

        // Update observable states from Execution's getters
        ra.value = execution.ra
        rb.value = execution.rb
        rz.value = execution.rz
        mar.value = execution.mar
        mdr.value = execution.mdr
        aluOp.value = execution.aluOp
        muxInr.value = execution.muxInr
    }


    fun memoryAccess() {
        execution.memoryAccess() // Run the Java memory access logic

        // Update observable state from Execution getters
        mar.value = execution.mar
        mdr.value = execution.mdr
        pcMuxPc.value = execution.pcMuxPc
        memoryMap.clear()
        memoryMap.putAll(execution.memory) // Optional: If you want memory changes visible in UI
        ry.value = execution.ry
        // Debugging
        println("Memory Access Stage (ViewModel Sync):")
        println("MAR: ${mar.value}, MDR: ${mdr.value}")
        println("PC Mux PC (updated): ${pcMuxPc.value}")
    }


    fun writeBack() {
        execution.writeBack()  // Call the Java logic


        registerFile.clear()
        registerFile.putAll(execution.registerFile)
        // Debug output for confirmation
        println("Write Back Stage (ViewModel Sync):")
        println("RY: ${ry.value}")
        println("Updated Register: ${valueRegister.value}")
    }
}

enum class PipelineStage {
    FETCH, DECODE, EXECUTE, MEMORY, WRITEBACK
}
