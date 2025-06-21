import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

public class PipelinedCPU {

    // --- Configuration Knobs ---
    private boolean pipeliningEnabled = true; // Knob 1: Enable/disable pipelining
    private boolean dataForwardingEnabled = true; // Knob 2: Enable/disable data forwarding
    private boolean printRegistersEnabled = false; // Knob 3: Print register file each cycle
    private boolean printPipelineRegsEnabled = true; // Knob 4: Print pipeline registers each cycle
    private int traceInstructionNum = -1; // Knob 5: Trace specific instruction number (-1 to disable)
    private boolean printBPUEnabled = false; // Knob 6: Print BPU details each cycle

    // --- Core Components ---
    private final Map<String, String> registerFile; // Register file (x0-x31)
    private final Map<String, String> textSegment; // Instruction Memory
    private final Map<String, String> dataMemory; // Data Memory
    private final BranchPredictor bpu;

    private long pc; // Program Counter (using long for unsigned 32-bit)
    private long clockCycle;
    private long instructionCount; // To track instruction number for Knob 5
    private boolean hazardStall; // Flag to indicate if pipeline is stalled
    private boolean dataForwardingStall;
    private boolean branchMispredictFlush; // Flag to signal flush due to misprediction

    // --- Pipeline Registers ---
    private IFIDRegister if_id_reg;
    private IDEXRegister id_ex_reg;
    private EXMEMRegister ex_mem_reg;
    private MEMWBRegister mem_wb_reg;
    private EXMEMRegister ex_debug;

    // --- Helper Constants ---
    private static final String NOP_INSTRUCTION = "0x00000000"; // NOP instruction representation
    private static final String ZERO_REG = "x0";
    private static final long INITIAL_SP = 0x7FFFFFDC; // Default stack pointer address

    // --- Constructor ---
    public PipelinedCPU() {
        registerFile = new HashMap<>();
        for (int i = 0; i < 32; i++) {
            registerFile.put("x" + i, formatHex(0));
        }
        registerFile.put("x2", formatHex(INITIAL_SP)); // Initialize Stack Pointer (sp)

        textSegment = new HashMap<>();
        dataMemory = new HashMap<>(); // Use this for data loads/stores
        bpu = new BranchPredictor();

        pc = 0x0; // Default starting PC
        clockCycle = 0;
        instructionCount = 0;
        hazardStall = false;
        branchMispredictFlush = false;

        // Initialize pipeline registers with NOPs or safe defaults
        if_id_reg = new IFIDRegister();
        id_ex_reg = new IDEXRegister();
        ex_mem_reg = new EXMEMRegister();
        mem_wb_reg = new MEMWBRegister();
    }

    // --- Pipeline Register Classes ---
    // (Contain data passed between stages)
    static class IFIDRegister {
        long instructionPC = 0;
        String instruction = NOP_INSTRUCTION;
        long nextPC = 0; // PC + 4
        //        long instructionNumber = 0; // For Knob 5
        boolean valid = false; // To handle stalls/flushes
        boolean predictedTaken = false; // For branch prediction
        long predictedTarget = 0; // Store the predicted target address

        @Override
        public String toString() {
            return String.format(
                    "IF/ID [Valid:%b]: PC=0x%08X, IR=%s, NextPC=0x%08X, PredTaken:%b, PredTarget=0x%08X",
                    valid, instructionPC, instruction, nextPC, predictedTaken, predictedTarget);
        }

        public void clear() {
            instruction = NOP_INSTRUCTION;
            valid = false;
//            instructionNumber = 0;
            predictedTaken = false;
            predictedTarget = 0;
        }
    }

    static class IDEXRegister {
        // Control Signals
        String aluOp = "NOP"; // Represents the operation
        boolean regWrite = false;
        boolean memRead = false;
        boolean memWrite = false;
        boolean branch = false; // Is it a branch instruction?
        boolean jump = false; // Is it JAL or JALR?
        boolean useImm = false; // Does ALU use immediate?
        int writeBackMux = 0; // 0: ALU result, 1: Mem data, 2: PC+4
        String memSize = "WORD"; // BYTE, HALF, WORD

        // Data
        long instructionPC = 0;
        long nextPC = 0; // For jumps/branches link address
        long readData1 = 0; // Value from rs1
        long readData2 = 0; // Value from rs2
        long immediate = 0; // Sign-extended immediate
        int rs1 = 0; // Register numbers (needed for forwarding checks)
        int rs2 = 0;
        int rd = 0; // Destination register number
        String debugInstruction = NOP_INSTRUCTION; // Store instruction string for debugging
        //        long instructionNumber = 0; // For Knob 5
        boolean valid = false;

        @Override
        public String toString() {
            return String.format(
                    "ID/EX [Valid:%b]: PC=0x%08X, Ctrl:[%s,RW:%b,MR:%b,MW:%b,Br:%b,Jmp:%b,Imm:%b,WBMux:%d,Sz:%s], "
                            +
                            "RVal1=0x%X, RVal2=0x%X, Imm=0x%X, rs1=x%d, rs2=x%d, rd=x%d, IR=%s",
                    valid, instructionPC, aluOp, regWrite, memRead, memWrite, branch, jump, useImm,
                    writeBackMux, memSize,
                    readData1, readData2, immediate, rs1, rs2, rd, debugInstruction.substring(2));
        }

        public void clear() {
            aluOp = "NOP";
            regWrite = false;
            memRead = false;
            memWrite = false;
            branch = false;
            jump = false;
            useImm = false;
            valid = false;
//            instructionNumber = 0;
            debugInstruction = NOP_INSTRUCTION;
        }
    }

    static class EXMEMRegister {
        // Control Signals
        boolean regWrite = false;
        boolean memRead = false;
        boolean memWrite = false;
        boolean branchTaken = false; // Actual outcome of branch
        String debugInstruction = NOP_INSTRUCTION; // Store instruction string for debugging
        int writeBackMux = 0;
        String memSize = "WORD";

        // Data
        long instructionPC = 0; // Needed for BPU update
        long aluResult = 0; // Result from ALU or calculated address
        long writeData = 0; // Data to be written to memory (from rs2/readData2)
        long branchTarget = 0; // Calculated target address for taken branches/jumps
        int rd = 0; // Destination register number
        boolean valid = false;
//        long instructionNumber = 0;

        @Override
        public String toString() {
            return String.format("EX/MEM [Valid:%b]: Ctrl:[RW:%b,MR:%b,MW:%b,BrTaken:%b,WBMux:%d,Sz:%s], " +
                            "ALURes=0x%X, WriteData=0x%X, BrTarget=0x%X, rd=x%d",
                    valid, regWrite, memRead, memWrite, branchTaken, writeBackMux, memSize,
                    aluResult, writeData, branchTarget, rd);
        }

        public EXMEMRegister() {

        }

        public EXMEMRegister(EXMEMRegister other) {
            this.regWrite = other.regWrite;
            this.memRead = other.memRead;
            this.memWrite = other.memWrite;
            this.branchTaken = other.branchTaken;
            this.debugInstruction = other.debugInstruction;
            this.writeBackMux = other.writeBackMux;
            this.memSize = other.memSize;

            this.instructionPC = other.instructionPC;
            this.aluResult = other.aluResult;
            this.writeData = other.writeData;
            this.branchTarget = other.branchTarget;
            this.rd = other.rd;
            this.valid = other.valid;
//            this.instructionNumber = other.instructionNumber;
        }

        public void clear() {
            regWrite = false;
            memRead = false;
            memWrite = false;
            valid = false;
//            instructionNumber = 0;
        }
    }

    static class MEMWBRegister {
        // Control Signals
        boolean regWrite = false;
        int writeBackMux = 0;

        // Data
        long instructionPC = 0; // Potentially useful for debugging
        long aluResult = 0; // Forwarded from EX/MEM
        long readData = 0; // Data read from memory
        int rd = 0; // Destination register number
        String debugInstruction = NOP_INSTRUCTION; // Store instruction string for debugging
        boolean valid = false;
//        long instructionNumber = 0;

        @Override
        public String toString() {
            return String.format("MEM/WB [Valid:%b]: Ctrl:[RW:%b,WBMux:%d], " +
                            "ALURes=0x%X, ReadData=0x%X, rd=x%d",
                    valid, regWrite, writeBackMux,
                    aluResult, readData, rd);
        }

        public void clear() {
            regWrite = false;
            valid = false;
//            instructionNumber = 0;
        }
    }

    // --- Branch Predictor ---
    static class BranchPredictor {
        // 1-bit predictor: 0 = Not Taken, 1 = Taken
        private final Map<Long, Integer> historyTable;
        // Branch Target Buffer (Maps PC -> Predicted Target)
        private final Map<Long, Long> targetBuffer;

        // Statistics
        long predictions = 0;
        long mispredictions = 0;
//        long btbHits = 0;
//        long btbMisses = 0;

        BranchPredictor() {
            historyTable = new HashMap<>();
            targetBuffer = new HashMap<>();
        }

        // Predict based on PC
        public boolean predictTaken(long pc) {
            predictions++;
            return historyTable.getOrDefault(pc, 0) == 1; // Default to Not Taken
        }

        // Check if the PC is in the BTB
        public boolean isInBTB(long pc) {
            return targetBuffer.containsKey(pc);
        }

        // Get predicted target from BTB
        public long getPredictedTarget(long pc) {
            if (targetBuffer.containsKey(pc)) {
//                btbHits++;
                return targetBuffer.get(pc);
            } else {
//                btbMisses++;
                return pc + 4; // Default to PC+4 if not in BTB
            }
        }

        // Update predictor based on actual outcome
        public void update(long pc, boolean actuallyTaken, long actualTarget) {
            int currentState = historyTable.getOrDefault(pc, 0);
            boolean predictedTaken = (currentState == 1);

            if (predictedTaken != actuallyTaken) {
                mispredictions++;
            }

            // Update state (simple 1-bit saturation)
            historyTable.put(pc, actuallyTaken ? 1 : 0);

            // Update BTB with actual target if branch was taken
            if (actuallyTaken) {
                targetBuffer.put(pc, actualTarget);
            }
        }

        @Override
        public String toString() {
            TreeMap<Long, Integer> sortedHistory = new TreeMap<>(historyTable);
            StringBuilder sb = new StringBuilder("BPU State: Predictions=" + predictions +
                    ", Mispredictions=" + mispredictions + "\n");
            sb.append(" History Table (PC -> State[0=NT,1=T]):\n");
            if (sortedHistory.isEmpty()) {
                sb.append("  <Empty>\n");
            } else {
                for (Map.Entry<Long, Integer> entry : sortedHistory.entrySet()) {
                    sb.append(String.format("  0x%08X -> %d\n", entry.getKey(), entry.getValue()));
                }
            }

            // Add BTB printing
            TreeMap<Long, Long> sortedBTB = new TreeMap<>(targetBuffer);
            sb.append(" Branch Target Buffer (PC -> Target):\n");
            if (sortedBTB.isEmpty()) {
                sb.append("  <Empty>\n");
            } else {
                for (Map.Entry<Long, Long> entry : sortedBTB.entrySet()) {
                    sb.append(String.format("  0x%08X -> 0x%08X\n", entry.getKey(), entry.getValue()));
                }
            }
            return sb.toString();
        }
    }

    // --- Utility Methods ---
    private static String formatHex(long value) {
        return String.format("0x%08X", value);
    }

    private long parseHex(String hex) {
        if (hex == null || !hex.startsWith("0x")) {
            // Handle error or return default
            System.err.println("Warning: Invalid hex string format: " + hex);
            return 0;
        }
        // Use parseUnsignedLong to handle addresses/values > 0x7FFFFFFF
        return Long.parseUnsignedLong(hex.substring(2), 16);
    }

    private long signExtend(String binary, int bitWidth) {
        long value = Long.parseLong(binary, 2);
        long mask = 1L << (bitWidth - 1);
        if ((value & mask) != 0) { // Check if sign bit is 1
            // Sign extend: fill higher bits with 1s
            long signBits = -1L << bitWidth; // Creates mask like 0xFFFFF000 for 12 bits
            value |= signBits;
        }
        return value;
    }

    // Little-Endian Memory Read/Write Helpers
    private long readMemory(long address, String size) {
        long value = 0;
        int bytes = size.equals("BYTE") ? 1 : size.equals("HALF") ? 2 : 4; // Assuming WORD is 4 bytes for RV32

        for (int i = 0; i < bytes; i++) {
            String byteAddressHex = formatHex(address + i);
            String byteStr = dataMemory.getOrDefault(byteAddressHex, "00"); // Default to 00 if not found
            long byteValue = Long.parseLong(byteStr, 16);
            value |= (byteValue << (i * 8)); // Assemble bytes in little-endian order
        }

        // Handle sign extension for byte/half loads
        if (size.equals("BYTE")) {
            if ((value & 0x80) != 0)
                value |= 0xFFFFFFFFFFFFFF00L; // Sign extend 8 bits
        } else if (size.equals("HALF")) {
            if ((value & 0x8000) != 0)
                value |= 0xFFFFFFFFFFFF0000L; // Sign extend 16 bits
        }
        // Word loads (LW) in RV32 are sign-extended from 32 bits, which Long already
        // handles if needed.
        // LBU, LHU would require zero-extension logic here if implemented.

        return value;
    }

    private void writeMemory(long address, long data, String size) {
        int bytes = size.equals("BYTE") ? 1 : size.equals("HALF") ? 2 : 4; // Assuming WORD is 4 bytes

        for (int i = 0; i < bytes; i++) {
            String byteAddressHex = formatHex(address + i);
            long byteValue = (data >> (i * 8)) & 0xFF; // Extract byte
            String byteStr = String.format("%02X", byteValue);
            dataMemory.put(byteAddressHex, byteStr);
        }
    }

    // --- Pipeline Stage Implementations ---

    private void instructionFetch() {
        if (hazardStall || dataForwardingStall) {
            // If stalled, do not fetch a new instruction
            return;
        }

        if (branchMispredictFlush) {
            // If flushing due to misprediction, the PC has already been updated
            // Just clear the flush flag and continue with the new PC
            branchMispredictFlush = false;
        }

        String instructionHex = textSegment.getOrDefault(formatHex(pc), NOP_INSTRUCTION);
        long currentPC = pc;
        long pcPlus4 = pc + 4;

//        boolean isRealInstruction = !instructionHex.equals(NOP_INSTRUCTION) && !instructionHex.equals("0xDEADBEEF");
//        long currentInstructionNumber = isRealInstruction ? instructionCount++ : -1;

        // Check if this might be a branch/jump based on opcode
        boolean mightBeBranch = false;
        long instructionVal = parseHex(instructionHex);
        int opcode = (int) (instructionVal & 0x7F); // bits 6:0

        // Check if it's a branch or jump instruction
        if (opcode == 0b1100011 || // B-Type (branches)
                opcode == 0b1101111 || // J-Type (JAL)
                opcode == 0b1100111) { // I-Type (JALR)
            mightBeBranch = true;
        }

        // Branch Prediction
        long predictedNextPC = pcPlus4; // Default: predict not taken
        boolean predictedTaken = false;

        // Check if the PC is in the BTB first
        if (bpu.isInBTB(currentPC)) {
            // This PC is in the BTB, so it's likely a branch/jump
            // Use branch predictor to decide if branch is taken
            predictedTaken = bpu.predictTaken(currentPC);
            if (predictedTaken) {
                // Get predicted target from BTB
                predictedNextPC = bpu.getPredictedTarget(currentPC);
                if (printBPUEnabled) {
                    System.out.printf("BPU: Predicting branch at 0x%08X as TAKEN to 0x%08X (BTB hit)\n",
                            currentPC, predictedNextPC);
                }
            } else if (printBPUEnabled) {
                System.out.printf("BPU: Predicting branch at 0x%08X as NOT TAKEN (BTB hit but not taken)\n", currentPC);
            }
        } else if (mightBeBranch) {
            // This looks like a branch but not in BTB
            // Use branch predictor to decide if branch is taken
            predictedTaken = bpu.predictTaken(currentPC);
            if (predictedTaken) {
                // We predict taken but don't know the target yet
                // We'll have to wait for the execute stage
                if (printBPUEnabled) {
                    System.out.printf("BPU: Predicting branch at 0x%08X as TAKEN but target unknown (BTB miss)\n",
                            currentPC);
                }
                // Since we don't know the target, we'll default to PC+4 and fix it later
                predictedNextPC = pcPlus4;
                // This will likely cause a mispredict, but that's expected for the first
                // encounter
            } else if (printBPUEnabled) {
                System.out.printf("BPU: Predicting branch at 0x%08X as NOT TAKEN\n", currentPC);
            }
        }

        // Prepare for next cycle
        if_id_reg.instructionPC = currentPC;
        if_id_reg.instruction = instructionHex;
        if_id_reg.nextPC = pcPlus4;
        if_id_reg.valid = true;
//        if_id_reg.instructionNumber = currentInstructionNumber;
        if_id_reg.predictedTaken = predictedTaken;
        if_id_reg.predictedTarget = predictedNextPC;

        // Update PC based on prediction
        pc = predictedTaken ? predictedNextPC : pcPlus4;
    }

    private void instructionDecode() {
        if (!if_id_reg.valid) {
            id_ex_reg.clear(); // Pass NOP downstream
            return;
        }

        // Take values from IF/ID Register
        String instruction = if_id_reg.instruction;
        long instructionPC = if_id_reg.instructionPC;
        long nextPC = if_id_reg.nextPC;

        // Clear fields for the new instruction
        id_ex_reg.clear();
        id_ex_reg.instructionPC = instructionPC;
        id_ex_reg.nextPC = nextPC;
        id_ex_reg.debugInstruction = instruction; // For debugging
//        id_ex_reg.instructionNumber = if_id_reg.instructionNumber;
        id_ex_reg.valid = true;

        if (instruction.equals(NOP_INSTRUCTION)) {
            id_ex_reg.aluOp = "NOP";
            id_ex_reg.valid = true; // NOP is valid but does nothing
            return; // Don't decode NOP
        }

        // --- Decode Instruction ---
        long instructionVal = parseHex(instruction);
        int opcode = (int) (instructionVal & 0x7F); // bits 6:0
        id_ex_reg.rd = (int) ((instructionVal >> 7) & 0x1F); // bits 11:7
        int funct3 = (int) ((instructionVal >> 12) & 0x7); // bits 14:12
        id_ex_reg.rs1 = (int) ((instructionVal >> 15) & 0x1F); // bits 19:15
        id_ex_reg.rs2 = (int) ((instructionVal >> 20) & 0x1F); // bits 24:20
        int funct7 = (int) ((instructionVal >> 25) & 0x7F); // bits 31:25

        // --- Read Registers ---
        String rs1Name = "x" + id_ex_reg.rs1;
        String rs2Name = "x" + id_ex_reg.rs2;
        id_ex_reg.readData1 = parseHex(registerFile.getOrDefault(rs1Name, formatHex(0)));
        id_ex_reg.readData2 = parseHex(registerFile.getOrDefault(rs2Name, formatHex(0)));

        // --- Generate Control Signals and Immediate ---
        id_ex_reg.regWrite = false; // Default off
        id_ex_reg.memRead = false;
        id_ex_reg.memWrite = false;
        id_ex_reg.branch = false;
        id_ex_reg.jump = false;
        id_ex_reg.useImm = false;
        id_ex_reg.writeBackMux = 0; // Default ALU result
        id_ex_reg.memSize = "WORD"; // Default

        switch (opcode) {
            case 0b0110011: // R-Type (add, sub, slt, xor, or, and, sll, srl, sra, mul, div, rem)
                id_ex_reg.aluOp = decodeRType(funct3, funct7);
                id_ex_reg.regWrite = true;
                break;

            case 0b0010011: // I-Type (addi, slti, xori, ori, andi, slli, srli, srai)
                id_ex_reg.immediate = signExtend(Long.toBinaryString((instructionVal >> 20)), 12);
                id_ex_reg.aluOp = decodeITypeArith(funct3, funct7); // funct7 needed for srai/srli
                id_ex_reg.useImm = true;
                id_ex_reg.regWrite = true;
                break;

            case 0b0000011: // I-Type Load (lb, lh, lw) <- Assuming RV32, no ld
                id_ex_reg.immediate = signExtend(Long.toBinaryString((instructionVal >> 20)), 12);
                id_ex_reg.aluOp = "ADD"; // For address calculation
                id_ex_reg.memRead = true;
                id_ex_reg.useImm = true;
                id_ex_reg.regWrite = true;
                id_ex_reg.writeBackMux = 1; // Write data from memory
                switch (funct3) {
                    case 0b000:
                        id_ex_reg.memSize = "BYTE";
                        break;
                    case 0b001:
                        id_ex_reg.memSize = "HALF";
                        break;
                    case 0b010:
                        id_ex_reg.memSize = "WORD";
                        break;
                    // LBU, LHU would set size but also need different handling in MEM/WB for zero
                    // extension
                    default:
                        id_ex_reg.aluOp = "INVALID";
                        break;
                }
                break;

            case 0b1100111: // I-Type JALR
                id_ex_reg.immediate = signExtend(Long.toBinaryString((instructionVal >> 20)), 12);
                id_ex_reg.aluOp = "JALR"; // Special handling in EX
                id_ex_reg.regWrite = true;
                id_ex_reg.useImm = true;
                id_ex_reg.jump = true;
                id_ex_reg.writeBackMux = 2; // Write PC+4
                break;

            case 0b0100011: // S-Type (sb, sh, sw)
                long imm_4_0 = (instructionVal >> 7) & 0x1F;
                long imm_11_5 = (instructionVal >> 25) & 0x7F;
                long immSVal = (imm_11_5 << 5) | imm_4_0;
                id_ex_reg.immediate = signExtend(Long.toBinaryString(immSVal), 12);
                id_ex_reg.aluOp = "ADD"; // For address calculation
                id_ex_reg.memWrite = true;
                id_ex_reg.useImm = true;
                switch (funct3) {
                    case 0b000:
                        id_ex_reg.memSize = "BYTE";
                        break;
                    case 0b001:
                        id_ex_reg.memSize = "HALF";
                        break;
                    case 0b010:
                        id_ex_reg.memSize = "WORD";
                        break;
                    default:
                        id_ex_reg.aluOp = "INVALID";
                        break;
                }
                break;

            case 0b1100011: // B-Type (beq, bne, blt, bge, bltu, bgeu)
                long imm_11 = (instructionVal >> 7) & 0x1;
                long imm_4_1 = (instructionVal >> 8) & 0xF;
                long imm_10_5 = (instructionVal >> 25) & 0x3F;
                long imm_12 = (instructionVal >> 31) & 0x1;
                long immBVal = (imm_12 << 12) | (imm_11 << 11) | (imm_10_5 << 5) | (imm_4_1 << 1);
                id_ex_reg.immediate = signExtend(Long.toBinaryString(immBVal), 13); // 13-bit immediate for branches
                id_ex_reg.aluOp = decodeBType(funct3);
                id_ex_reg.branch = true;
                break;

            case 0b0110111: // U-Type (LUI)
                id_ex_reg.immediate = signExtend(Long.toBinaryString((instructionVal & 0xFFFFF000L)), 32); // imm[31:12]
                // << 12
                id_ex_reg.aluOp = "LUI"; // Special handling in EX
                id_ex_reg.regWrite = true;
                id_ex_reg.useImm = true; // Pass immediate through
                break;

            case 0b0010111: // U-Type (AUIPC)
                id_ex_reg.immediate = signExtend(Long.toBinaryString((instructionVal & 0xFFFFF000L)), 32);
                id_ex_reg.aluOp = "AUIPC"; // Special handling in EX
                id_ex_reg.regWrite = true;
                id_ex_reg.useImm = true; // Pass immediate through
                break;

            case 0b1101111: // J-Type (JAL)
                long imm_20 = (instructionVal >> 31) & 0x1;
                long imm_10_1 = (instructionVal >> 21) & 0x3FF;
                imm_11 = (instructionVal >> 20) & 0x1;
                long imm_19_12 = (instructionVal >> 12) & 0xFF;
                long immJVal = (imm_20 << 20) | (imm_19_12 << 12) | (imm_11 << 11) | (imm_10_1 << 1);
                id_ex_reg.immediate = signExtend(Long.toBinaryString(immJVal), 21); // 21-bit immediate for JAL
                id_ex_reg.aluOp = "JAL"; // Special handling in EX
                id_ex_reg.regWrite = true;
                id_ex_reg.jump = true;
                id_ex_reg.writeBackMux = 2; // Write PC+4
                break;

            default:
                System.err.println("Error: Unsupported opcode " + Integer.toBinaryString(opcode) + " at PC "
                        + formatHex(instructionPC));
                id_ex_reg.aluOp = "INVALID";
                id_ex_reg.valid = false; // Mark as invalid
                break;
        }

        // --- Hazard Detection and Handling ---
        if (pipeliningEnabled) {
            detectAndHandleHazards(); // Modifies hazardStall flag
        }

        // --- Data Forwarding Hazard Detection (when forwarding is disabled) ---
        dataForwardingStall = false; // Reset every cycle

        if (!dataForwardingEnabled && id_ex_reg.aluOp != null && !id_ex_reg.aluOp.equals("NOP")) {
            int rs1 = id_ex_reg.rs1;
            int rs2 = id_ex_reg.rs2;

            boolean rs1Needed = true; // Usually needed
            boolean rs2Needed = !id_ex_reg.useImm; // Needed unless using immediate
            boolean storeDataNeedsRs2 = id_ex_reg.memWrite; // Store uses rs2 as data

            // EX/MEM forwarding hazard
            if (ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd != 0) {
                if ((rs1Needed && ex_mem_reg.rd == rs1) ||
                        ((rs2Needed || storeDataNeedsRs2) && ex_mem_reg.rd == rs2)) {
                    dataForwardingStall = true;
                }
            }

            // MEM/WB forwarding hazard (only if EX/MEM isn’t already forwarding that reg)
            if (mem_wb_reg.valid && mem_wb_reg.regWrite && mem_wb_reg.rd != 0) {
                if ((rs1Needed && mem_wb_reg.rd == rs1 &&
                        !(ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd == rs1)) ||
                        ((rs2Needed || storeDataNeedsRs2) && mem_wb_reg.rd == rs2 &&
                                !(ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd == rs2))) {
                    dataForwardingStall = true;
                }
            }
        }

        // If stalled, convert the instruction entering EX stage into a NOP
        if (hazardStall || dataForwardingStall) {
            id_ex_reg.clear(); // Turn into NOP
            id_ex_reg.valid = true; // Still valid stage, just NOP
            // Keep IF/ID register stalled (don't clear IF stage's output)
        } else {
            // Only clear IF/ID if not stalled
            if_id_reg.valid = false; // Consume instruction from IF/ID
        }
    }

    // --- Helper Decode Methods ---
    private String decodeRType(int funct3, int funct7) {
        switch (funct3) {
            case 0b000:
                return (funct7 == 0b0000000) ? "ADD"
                        : (funct7 == 0b0100000) ? "SUB" : (funct7 == 0b0000001) ? "MUL" : "INVALID";
            case 0b001:
                return (funct7 == 0b0000000) ? "SLL" : (funct7 == 0b0000001) ? "MULH" : "INVALID"; // MULH needs higher
            // bits
            case 0b010:
                return (funct7 == 0b0000000) ? "SLT" : (funct7 == 0b0000001) ? "MULHSU" : "INVALID";
            case 0b011:
                return (funct7 == 0b0000000) ? "SLTU" : (funct7 == 0b0000001) ? "MULHU" : "INVALID"; // SLTU needs
            // unsigned
            // comparison
            case 0b100:
                return (funct7 == 0b0000000) ? "XOR" : (funct7 == 0b0000001) ? "DIV" : "INVALID";
            case 0b101:
                return (funct7 == 0b0000000) ? "SRL"
                        : (funct7 == 0b0100000) ? "SRA" : (funct7 == 0b0000001) ? "DIVU" : "INVALID";
            case 0b110:
                return (funct7 == 0b0000000) ? "OR" : (funct7 == 0b0000001) ? "REM" : "INVALID";
            case 0b111:
                return (funct7 == 0b0000000) ? "AND" : (funct7 == 0b0000001) ? "REMU" : "INVALID";
            default:
                return "INVALID";
        }
    }

    private String decodeITypeArith(int funct3, int funct7) {
        switch (funct3) {
            case 0b000:
                return "ADDI";
            case 0b010:
                return "SLTI";
            case 0b011:
                return "SLTIU"; // Needs unsigned comparison
            case 0b100:
                return "XORI";
            case 0b110:
                return "ORI";
            case 0b111:
                return "ANDI";
            case 0b001:
                return "SLLI"; // funct7 is 0 for SLLI
            case 0b101:
                return (funct7 == 0b0000000) ? "SRLI" : (funct7 == 0b0100000) ? "SRAI" : "INVALID"; // Need funct7 for
            // SRLI/SRAI
            default:
                return "INVALID";
        }
    }

    private String decodeBType(int funct3) {
        switch (funct3) {
            case 0b000:
                return "BEQ";
            case 0b001:
                return "BNE";
            case 0b100:
                return "BLT";
            case 0b101:
                return "BGE";
            case 0b110:
                return "BLTU"; // Needs unsigned comparison
            case 0b111:
                return "BGEU"; // Needs unsigned comparison
            default:
                return "INVALID";
        }
    }

    private void execute() {
        ex_debug = new EXMEMRegister(ex_mem_reg);
        if (!id_ex_reg.valid) {
            ex_mem_reg.clear();
            return;
        }
        if (id_ex_reg.debugInstruction.equals("0xDEADBEEF")) {
            ex_mem_reg.valid = true;
            ex_mem_reg.debugInstruction = "0xDEADBEEF";
            return;
        }

        // --- Forwarding Logic ---
        long operand1 = id_ex_reg.readData1;
        long operand2 = id_ex_reg.useImm ? id_ex_reg.immediate : id_ex_reg.readData2;
        int sourceReg1 = id_ex_reg.rs1;
        int sourceReg2 = id_ex_reg.rs2;

        if (pipeliningEnabled && dataForwardingEnabled) {
            // Check EX/MEM Hazard (Result from previous instruction)
            if (ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd != 0) {
                if (ex_mem_reg.rd == sourceReg1) {
                    operand1 = ex_mem_reg.aluResult; // Forward ALU result from EX/MEM
                    // System.out.println("Forward EX->EX op1");
                }
                // Check if operand2 comes from a register (not immediate and not store)
                if (!id_ex_reg.useImm && !id_ex_reg.memWrite && ex_mem_reg.rd == sourceReg2) {
                    operand2 = ex_mem_reg.aluResult; // Forward ALU result from EX/MEM
                    // System.out.println("Forward EX->EX op2");
                }
                // Forwarding for the data to be stored (rs2 for S-type)
                if (id_ex_reg.memWrite && ex_mem_reg.rd == sourceReg2) {
                    id_ex_reg.readData2 = ex_mem_reg.aluResult; // Update the value to be stored
                    // System.out.println("Forward EX->EX store data (rs2)");
                }
            }

            // Check MEM/WB Hazard (Result from two instructions prior)
            // Important: Don't forward if the EX/MEM stage already forwarded for the same
            // register
            if (mem_wb_reg.valid && mem_wb_reg.regWrite && mem_wb_reg.rd != 0) {
                long wbData = (mem_wb_reg.writeBackMux == 1) ? mem_wb_reg.readData : mem_wb_reg.aluResult;

                if (mem_wb_reg.rd == sourceReg1
                        && !(ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd == sourceReg1)) {
                    operand1 = wbData; // Forward data from MEM/WB
                    // System.out.println("Forward MEM->EX op1");
                }
                if (!id_ex_reg.useImm && !id_ex_reg.memWrite && mem_wb_reg.rd == sourceReg2
                        && !(ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd == sourceReg2)) {
                    operand2 = wbData; // Forward data from MEM/WB
                    // System.out.println("Forward MEM->EX op2");
                }
                // Forwarding for the data to be stored (rs2 for S-type)
                if (id_ex_reg.memWrite && mem_wb_reg.rd == sourceReg2
                        && !(ex_mem_reg.valid && ex_mem_reg.regWrite && ex_mem_reg.rd == sourceReg2)) {
                    id_ex_reg.readData2 = wbData; // Update the value to be stored
                    // System.out.println("Forward MEM->EX store data (rs2)");
                }
            }
        }
        ex_mem_reg.clear();
        ex_mem_reg.valid = true;
//        ex_mem_reg.instructionNumber = id_ex_reg.instructionNumber;
        ex_mem_reg.instructionPC = id_ex_reg.instructionPC; // Pass PC for BPU update

        // --- ALU Execution ---
        long aluResult = 0;
        boolean branchConditionMet = false;
        long branchTarget = 0;
        long linkAddress = id_ex_reg.nextPC; // PC+4

        switch (id_ex_reg.aluOp) {
            // Arithmetic
            case "ADDI":
                aluResult = operand1 + operand2;
                break;
            case "SUB":
                aluResult = operand1 - operand2;
                break;
            case "MUL":
                aluResult = operand1 * operand2;
                break; // Simple multiplication
            // case "DIV": ... handle division by zero ...
            // case "REM": ... handle division by zero ...
            // Logical
            case "XOR":
            case "XORI":
                aluResult = operand1 ^ operand2;
                break;
            case "OR":
            case "ORI":
                aluResult = operand1 | operand2;
                break;
            case "AND":
            case "ANDI":
                aluResult = operand1 & operand2;
                break;
            // Shifts (Mask shift amount to 5 bits for RV32)
            case "SLL":
            case "SLLI":
                aluResult = operand1 << (operand2 & 0x1F);
                break;
            case "SRL":
            case "SRLI":
                aluResult = operand1 >>> (operand2 & 0x1F);
                break; // Logical right shift
            case "SRA":
            case "SRAI":
                aluResult = operand1 >> (operand2 & 0x1F);
                break; // Arithmetic right shift
            // Comparisons
            case "SLT":
            case "SLTI":
                aluResult = (operand1 < operand2) ? 1 : 0;
                break;
            case "SLTU":
            case "SLTIU":
                aluResult = (Long.compareUnsigned(operand1, operand2) < 0) ? 1 : 0;
                break;
            // Load/Store Address Calculation
            case "LOAD_ADDR":
            case "STORE_ADDR": // If we separated address calc
                aluResult = operand1 + operand2;
                break;
            // Branches
            case "BEQ":
                branchConditionMet = (operand1 == operand2);
                break;
            case "BNE":
                branchConditionMet = (operand1 != operand2);
                break;
            case "BLT":
                branchConditionMet = (operand1 < operand2);
                break;
            case "BGE":
                branchConditionMet = (operand1 >= operand2);
                break;
            case "BLTU":
                branchConditionMet = (Long.compareUnsigned(operand1, operand2) < 0);
                break;
            case "BGEU":
                branchConditionMet = (Long.compareUnsigned(operand1, operand2) >= 0);
                break;
            // Jumps
            case "JAL":
                aluResult = linkAddress; // Store PC+4 in rd
                branchTarget = id_ex_reg.instructionPC + id_ex_reg.immediate;
                branchConditionMet = true; // JAL always 'taken'
                break;
            case "JALR":
                aluResult = linkAddress; // Store PC+4 in rd
                // Target address is (rs1 + imm) & ~1 (lowest bit cleared)
                branchTarget = (operand1 + id_ex_reg.immediate) & ~1L;
                branchConditionMet = true; // JALR always 'taken'
                break;
            // U-Types
            case "LUI":
                aluResult = id_ex_reg.immediate;
                break; // Result is just the immediate
            case "AUIPC":
                aluResult = id_ex_reg.instructionPC + id_ex_reg.immediate;
                break; // PC + imm
            // Address calculation for Load/Store (if using generic ADD)
            case "ADD": // Can be ADDI, ADD (R), or address calc for Load/Store
                if (id_ex_reg.memRead || id_ex_reg.memWrite) {
                    aluResult = operand1 + operand2; // Address calculation
                } else {
                    aluResult = operand1 + operand2; // Regular ADD/ADDI
                }
                break;
            case "NOP": // Do nothing
                break;
            case "INVALID": // Handle invalid op
                System.err.println("Executing INVALID operation!");
                break;
            default: // Should not happen if decode is correct
                System.err.println("Unknown ALU operation in EX: " + id_ex_reg.aluOp);
                break;
        }

        if (id_ex_reg.branch || id_ex_reg.jump) {
            if (id_ex_reg.branch) { // B-Type
                branchTarget = id_ex_reg.instructionPC + id_ex_reg.immediate;
                ex_mem_reg.branchTaken = branchConditionMet;

                // Get the prediction that was made during fetch
                // boolean predictedTaken = if_id_reg.predictedTaken;
                boolean predictedTaken = (pipeliningEnabled) ? bpu.predictTaken(id_ex_reg.instructionPC)
                        : if_id_reg.predictedTaken;
                long predictedTarget = (pipeliningEnabled) ? bpu.getPredictedTarget(id_ex_reg.instructionPC)
                        : if_id_reg.predictedTarget;
                // System.out.println("Important !!!!: " + predictedTarget + " wanted : "
                // + bpu.getPredictedTarget(id_ex_reg.instructionPC));
                // long predictedTarget = bpu.getPredictedTarget(id_ex_reg.instructionPC);
                // Update branch predictor with actual outcome
                bpu.update(id_ex_reg.instructionPC, branchConditionMet, branchTarget);

                // Check if prediction was correct
                boolean targetMismatch = branchConditionMet && (predictedTarget != branchTarget);
                if (predictedTaken != branchConditionMet || targetMismatch) {
                    // Misprediction! Need to flush and correct PC
                    branchMispredictFlush = true;

                    // Set correct PC for next fetch
                    pc = branchConditionMet ? branchTarget : id_ex_reg.nextPC;

                    if (printBPUEnabled || printPipelineRegsEnabled) {
                        if (targetMismatch) {
                            System.out.printf(
                                    "BRANCH TARGET MISPREDICT at 0x%08X: Predicted 0x%08X, Actual 0x%08X. Correcting PC.\n",
                                    id_ex_reg.instructionPC, predictedTarget, branchTarget);
                        } else {
                            System.out.printf(
                                    "BRANCH DIRECTION MISPREDICT at 0x%08X: Predicted %s, Actual %s. Correcting PC to 0x%08X\n",
                                    id_ex_reg.instructionPC,
                                    predictedTaken ? "TAKEN" : "NOT TAKEN",
                                    branchConditionMet ? "TAKEN" : "NOT TAKEN",
                                    pc);
                        }
                    }
                }
            } else { // JAL or JALR (jump = true)
                if (id_ex_reg.aluOp.equals("JAL")) {
                    branchTarget = id_ex_reg.instructionPC + id_ex_reg.immediate;
                } else { // JALR
                    // Target address is (rs1 + imm) & ~1 (lowest bit cleared)
                    branchTarget = (operand1 + id_ex_reg.immediate) & ~1L;
                }

                ex_mem_reg.branchTaken = true; // Jumps are always 'taken'

                // Get the prediction that was made during fetch
                long predictedTarget = (pipeliningEnabled) ? bpu.getPredictedTarget(id_ex_reg.instructionPC)
                        : if_id_reg.predictedTarget;
                // long predictedTarget = bpu.getPredictedTarget(id_ex_reg.instructionPC);

                // Update BTB for this jump
                bpu.update(id_ex_reg.instructionPC, true, branchTarget);

                // Check if we predicted this jump correctly
                if (predictedTarget != branchTarget) {
                    // Jump target misprediction
                    branchMispredictFlush = true;
                    pc = branchTarget;

                    if (printBPUEnabled || printPipelineRegsEnabled) {
                        System.out.printf(
                                "JUMP TARGET MISPREDICT at 0x%08X: Predicted 0x%08X, Actual 0x%08X. Correcting PC.\n",
                                id_ex_reg.instructionPC, predictedTarget, branchTarget);
                    }
                }
            }

            // Store branch/jump target for debugging
            ex_mem_reg.branchTarget = branchTarget;
        }

        // --- Prepare EX/MEM Register ---
        ex_mem_reg.aluResult = aluResult;
        ex_mem_reg.writeData = id_ex_reg.useImm ? 0 : operand2; // Get potentially forwarded rs2 value for Stores
        if (id_ex_reg.memWrite) {
            ex_mem_reg.writeData = id_ex_reg.readData2; // Use the potentially forwarded value
        }
        ex_mem_reg.rd = id_ex_reg.rd;
        ex_mem_reg.branchTarget = branchTarget; // Pass target along (debug/unused now)

        // Pass control signals
        ex_mem_reg.regWrite = id_ex_reg.regWrite;
        ex_mem_reg.memRead = id_ex_reg.memRead;
        ex_mem_reg.memWrite = id_ex_reg.memWrite;
        ex_mem_reg.debugInstruction = id_ex_reg.debugInstruction;
        ex_mem_reg.writeBackMux = id_ex_reg.writeBackMux;
        ex_mem_reg.memSize = id_ex_reg.memSize;

        // Consume instruction from ID/EX
        id_ex_reg.valid = false;
    }

    private void memoryAccess() {
        if (!ex_mem_reg.valid) {
            mem_wb_reg.clear();
            return;
        }
        mem_wb_reg.clear();
        mem_wb_reg.valid = true;
        mem_wb_reg.debugInstruction = ex_mem_reg.debugInstruction;
//        mem_wb_reg.instructionNumber = ex_mem_reg.instructionNumber;
        mem_wb_reg.instructionPC = ex_mem_reg.instructionPC; // Pass PC for debug

        long addr = ex_mem_reg.aluResult; // Address comes from ALU result
        long writeData = ex_mem_reg.writeData; // Data to write for stores

        // --- Memory Operation ---
        long readDataResult = 0;
        if (ex_mem_reg.memRead) {
            readDataResult = readMemory(addr, ex_mem_reg.memSize);
            if (printPipelineRegsEnabled
                    || (traceInstructionNum != -1 && mem_wb_reg.debugInstruction.equals(textSegment.getOrDefault(formatHex(traceInstructionNum), NOP_INSTRUCTION)))) {
                System.out.printf("      MEM: Read %s from 0x%X, Value=0x%X\n", ex_mem_reg.memSize, addr,
                        readDataResult);
            }
        } else if (ex_mem_reg.memWrite) {
            writeMemory(addr, writeData, ex_mem_reg.memSize);
            if (printPipelineRegsEnabled
                    || (traceInstructionNum != -1 && mem_wb_reg.debugInstruction.equals(textSegment.getOrDefault(formatHex(traceInstructionNum), NOP_INSTRUCTION)))) {
                System.out.printf("      MEM: Wrote %s to 0x%X, Value=0x%X\n", ex_mem_reg.memSize, addr, writeData);
            }
        }

        // --- Prepare MEM/WB Register ---
        mem_wb_reg.aluResult = ex_mem_reg.aluResult; // Pass ALU result through
        mem_wb_reg.readData = readDataResult; // Pass data read from memory
        mem_wb_reg.rd = ex_mem_reg.rd;

        // Pass control signals
        mem_wb_reg.regWrite = ex_mem_reg.regWrite;
        mem_wb_reg.writeBackMux = ex_mem_reg.writeBackMux;

        // Consume instruction from EX/MEM
        // ex_mem_reg.valid = false;
    }

    private void writeBack() {
        if (!mem_wb_reg.valid || !mem_wb_reg.regWrite || mem_wb_reg.rd == 0) {
            // Consume instruction from MEM/WB even if not writing
            mem_wb_reg.valid = false;
            return; // Skip write if RegWrite is false, rd is x0, or stage is invalid
        }

        // --- Select Data to Write ---
        long writeData;
        switch (mem_wb_reg.writeBackMux) {
            case 0: // ALU result (R-Type, I-Type Arith, LUI, AUIPC)
                writeData = mem_wb_reg.aluResult;
                break;
            case 1: // Data from Memory (Loads)
                writeData = mem_wb_reg.readData;
                break;
            case 2: // PC + 4 (JAL, JALR) -> Link address is stored in aluResult by EX stage
                writeData = mem_wb_reg.aluResult;
                break;
            default:
                System.err.println("Error: Invalid writeBackMux value in WB: " + mem_wb_reg.writeBackMux);
                writeData = 0; // Default to 0 on error
                break;
        }

        // --- Write to Register File ---
        String rdName = "x" + mem_wb_reg.rd;
        registerFile.put(rdName, formatHex(writeData));

        if (printPipelineRegsEnabled || printRegistersEnabled
                || (traceInstructionNum != -1 && mem_wb_reg.debugInstruction.equals(textSegment.getOrDefault(formatHex(traceInstructionNum), NOP_INSTRUCTION)))) {
            System.out.printf("      WB: Write 0x%X to %s \n", writeData, rdName);
        }

        // Consume instruction from MEM/WB
        // mem_wb_reg.valid = false;
    }

    // --- Hazard Detection and Stalling/Forwarding ---
    private void detectAndHandleHazards() {
        hazardStall = false; // Reset stall flag for this cycle detection

        // --- Load-Use Hazard Detection (Stall) ---
        // Check if instruction in ID needs a result from a Load instruction in EX
        if (id_ex_reg.valid && ex_mem_reg.valid && ex_mem_reg.memRead && ex_mem_reg.regWrite && ex_mem_reg.rd != 0) {
            boolean rs1Match = id_ex_reg.rs1 == ex_mem_reg.rd;
            // rs2Match is tricky: need to know if ID stage *uses* rs2 (R-type, S-type,
            // B-type)
            boolean idUsesRs2 = (id_ex_reg.aluOp.equals("ADD") && id_ex_reg.memWrite) // Store Address
                    || (!id_ex_reg.useImm && !id_ex_reg.jump && !id_ex_reg.aluOp.equals("LUI")
                    && !id_ex_reg.aluOp.equals("AUIPC")); // R-type, B-type use rs2
            boolean rs2Match = idUsesRs2 && (id_ex_reg.rs2 == ex_mem_reg.rd);

            if (rs1Match || rs2Match) {
                // Load-Use Hazard Detected!
                hazardStall = true;
                if (printPipelineRegsEnabled || traceInstructionNum != -1) { // Print only if tracing is on
                    System.out.println(">>> Load-Use Hazard Detected! Stalling pipeline. <<<");
                    System.out.printf("    ID wants r%d/r%d, EX is LW to r%d\n", id_ex_reg.rs1, id_ex_reg.rs2,
                            ex_mem_reg.rd);
                }
            }
        }

        // --- Data Forwarding is handled within the EX stage ---
        // The logic here primarily focuses on conditions requiring a stall.
        // More complex stall conditions (e.g., structural hazards if memory/ALU were
        // not fully pipelined) could be added here.
    }

    // --- Pipeline Flushing on Mispredict ---
    private void handleFlush() {
        if (branchMispredictFlush) {
            if (printPipelineRegsEnabled) {
                System.out.println(">>> Branch/Jump Misprediction! Flushing pipeline. <<<");
            }

            // Clear the IF/ID and ID/EX stages to insert NOPs
            if_id_reg.clear();
            id_ex_reg.clear();

            // The PC has already been corrected by the EX stage logic
            branchMispredictFlush = false; // Reset the flag
            hazardStall = false; // Flushing overrides stalling
        }
    }

    // --- Simulation Execution ---

    public void run() {
        if (pipeliningEnabled) {
            runPipeline();
        } else {
            runSingleCycle();
        }
        printFinalState();
    }

    private void runPipeline() {
        System.out.println("--- Starting Pipelined Simulation ---");
        System.out.printf("Knobs: Forwarding=%b, RegPrint=%b, PipePrint=%b, BPPrint=%b, TraceInst#=%d\n",
                dataForwardingEnabled, printRegistersEnabled, printPipelineRegsEnabled, printBPUEnabled,
                traceInstructionNum);

        // long maxCycles = instructionCount * 5 + 100; // Estimate max cycles
        // if (textSegment.isEmpty()) {
        // maxCycles = 10; // Or some small number if no instructions loaded
        // }

        while (!mem_wb_reg.debugInstruction.equals("0xDEADBEEF")) {
            clockCycle++;
            System.out.println("\n--- Cycle: " + clockCycle + " ---");
            // Execute stages in reverse order for correct data flow within a cycle
            execute(); // May set branchMispredictFlush flag
            EXMEMRegister temp = new EXMEMRegister(ex_mem_reg);
            ex_mem_reg = ex_debug;
            writeBack();
            memoryAccess();
            ex_mem_reg = temp;

            // Handle flush AFTER execute but BEFORE decode and fetch
            handleFlush();

            instructionDecode(); // May set hazardStall flag
            instructionFetch(); // Uses PC (potentially updated by EX)

            // --- Printing based on Knobs ---
            String tracePc = textSegment.getOrDefault(formatHex(traceInstructionNum), NOP_INSTRUCTION);
            if (printPipelineRegsEnabled) {
                if (traceInstructionNum == -1) {
                    System.out.println(if_id_reg.toString());
                    System.out.println(id_ex_reg.toString());
                    System.out.println(ex_mem_reg.toString());
                    System.out.println(mem_wb_reg.toString());
                }
                else if (tracePc.equals(if_id_reg.instruction)) {
                    System.out.println(if_id_reg.toString());
                }
                else if (tracePc.equals(id_ex_reg.debugInstruction)) {
                    System.out.println(id_ex_reg.toString());
                }
                else if (tracePc.equals(ex_mem_reg.debugInstruction)) {
                    System.out.println(ex_mem_reg.toString());
                }
                else if (tracePc.equals(mem_wb_reg.debugInstruction)) {
                    System.out.println(mem_wb_reg.toString());
                }
            }
//            else if (traceInstructionNum != -1) {
//                // Print specific instruction trace
//                if (if_id_reg.valid && if_id_reg.instructionNumber == traceInstructionNum)
//                    System.out.println(if_id_reg.toString());
//                if (id_ex_reg.valid && id_ex_reg.instructionNumber == traceInstructionNum)
//                    System.out.println(id_ex_reg.toString());
//                if (ex_mem_reg.valid && ex_mem_reg.instructionNumber == traceInstructionNum)
//                    System.out.println(ex_mem_reg.toString());
//                if (mem_wb_reg.valid && mem_wb_reg.instructionNumber == traceInstructionNum)
//                    System.out.println(mem_wb_reg.toString());
//            }

            if (printRegistersEnabled) {
                printRegisterFileState();
            }
            if (printBPUEnabled) {
                System.out.println(bpu.toString());
            }

            // --- Termination Check ---
            // Check if pipeline is empty (all stages hold invalid or NOP) and no more
            // instructions fetched
            boolean pipelineEmpty = !if_id_reg.valid && !id_ex_reg.valid && !ex_mem_reg.valid && !mem_wb_reg.valid;
            boolean noMoreInstructions = textSegment.getOrDefault(formatHex(pc), NOP_INSTRUCTION)
                    .equals(NOP_INSTRUCTION);

            // Crude termination: Stop if PC points to NOP and pipeline is empty
            if (pipelineEmpty && noMoreInstructions) {
                System.out.println("\n--- Pipeline Empty and PC points to NOP. Simulation finished. ---");
                break;
            }
            // if (clockCycle >= maxCycles && maxCycles > 10) {
            // System.err.println("Warning: Maximum cycle limit reached (" + maxCycles + ").
            // Terminating simulation.");
            // break;
            // }
        }
    }

    // Simplified single-cycle execution (like Phase 2)
    private void runSingleCycle() {
        System.out.println("--- Starting Single-Cycle Simulation (Pipelining Disabled) ---");
        // Use original code structure variables temporarily for mimicry
        String ir;
        long pcTemp = 0;
        long currentPC = pc;
        // Reset pipeline regs if switching dynamically (unlikely use case)
        if_id_reg.clear();
        id_ex_reg.clear();
        ex_mem_reg.clear();
        mem_wb_reg.clear();

        while (true) {
            clockCycle++;
            System.out.println("\n--- Cycle: " + clockCycle + " (PC=" + formatHex(currentPC) + ") ---");

            // 1. Fetch
            // instructionFetch();
            ir = textSegment.getOrDefault(formatHex(currentPC), NOP_INSTRUCTION);
            System.out.println("Fetch: IR = " + ir);
            if (ir.equals(NOP_INSTRUCTION) || ir.equals("0xDEADBEEF")) {
                System.out.println("Termination: " + ir);
                break;
            }
            pcTemp = currentPC + 4; // Calculate potential next PC

            // Reset temporary 'stage' outputs before decode/execute
            id_ex_reg.clear();
            ex_mem_reg.clear();
            mem_wb_reg.clear();

            // 2. Decode (Directly sets temporary state for execute)
            if_id_reg.instruction = ir; // Simulate passing IR
            if_id_reg.instructionPC = currentPC;
            if_id_reg.nextPC = pcTemp;
            if_id_reg.valid = true;
            instructionDecode(); // Decodes into id_ex_reg directly
            System.out.println("Decode: " + id_ex_reg.toString());
            if (!id_ex_reg.valid || id_ex_reg.aluOp.equals("INVALID")) {
                System.err.println("Decode Error. Halting.");
                break;
            }

            // 3. Execute (Reads from id_ex_reg, writes to ex_mem_reg)
            // In single cycle, PC update logic is simpler
            pc = pcTemp; // Assume PC+4 unless branch/jump overrides
            execute(); // execute() updates 'pc' directly if branch/jump occurs
            System.out.println("Execute: " + ex_mem_reg.toString());
            if (!ex_mem_reg.valid) { // Should not happen unless decode failed
                System.err.println("Execute Error. Halting.");
                break;
            }

            // 4. Memory Access (Reads from ex_mem_reg, writes to mem_wb_reg)
            memoryAccess();
            System.out.println("Memory: " + mem_wb_reg.toString());
            if (!mem_wb_reg.valid) { // Should not happen
                System.err.println("Memory Stage Error. Halting.");
                break;
            }

            // 5. Write Back (Reads from mem_wb_reg)
            writeBack();
            // writeBack() prints its action if needed

            // Update PC for the *next* cycle's fetch
            currentPC = pc;

            if (printRegistersEnabled) {
                printRegisterFileState();
            }

            if (clockCycle > 5000) { // Safety break
                System.err.println("Error: Single-cycle execution exceeded 5000 cycles.");
                break;
            }
        }
    }

    // --- Input Parsing ---
    public void parseMachineCodeFromFile(String filePath) {
        textSegment.clear();
        dataMemory.clear(); // Clear previous memory state
        long basePC = -1; // Track the first instruction address

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Skip empty lines or comments
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                // Split the line by whitespace to get potential address/value parts
                // Handles formats like "0xADDR 0xVALUE ..." or "0xADDR 0xBYTE"
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    System.err.println("Skipping invalid line (not enough parts): " + line);
                    continue;
                }

                String addressStr = parts[0];
                String valueStr = parts[1]; // This is either instruction hex or data byte hex

                // Basic validation for hex prefixes
                if (!addressStr.startsWith("0x") || !valueStr.startsWith("0x")) {
                    System.err.println("Skipping invalid line (hex format error): " + line);
                    continue;
                }

                long address = Long.parseUnsignedLong(addressStr.substring(2), 16);
                long value = Long.parseUnsignedLong(valueStr.substring(2), 16); // Parse the second part

                // Determine if it's an instruction/marker line (contains ',') or data line
                if (line.contains(",")) {
                    // Instruction line or the DEADBEEF marker line
                    // Example: 0x0 0x100000B7 , lui x1 0x10000 ...
                    // Example: 0x00000058 0xdeadbeef , ends

                    // if (value == 0xDEADBEEFL) {
                    // // Found the marker indicating the end of the text segment.
                    // System.out.println("Found end of text segment marker (0xDEADBEEF) at " +
                    // formatHex(address));
                    // // Subsequent lines without commas will be treated as data
                    // continue; // Don't store the marker itself
                    // }

                    // Treat as a regular instruction
                    if (basePC == -1) {
                        basePC = address; // Set starting PC to the address of the first instruction
                    }
                    long instruction = value; // The parsed value is the instruction word
                    textSegment.put(formatHex(address), formatHex(instruction));

                    // Also load instruction bytes into dataMemory (little-endian)
                    // This supports inspection or potential self-modifying code.
                    for (int i = 0; i < 4; i++) {
                        long byteVal = (instruction >> (i * 8)) & 0xFF;
                        dataMemory.put(formatHex(address + i), String.format("%02X", byteVal));
                    }
                } else {
                    // Data memory line (no comma expected)
                    // Example: 0x10000000 0x0A
                    // The parsed 'value' should be the byte value for this address.

                    // Validate that the value is indeed a byte for data memory.
                    if (value > 0xFF) {
                        System.err.println(
                                "Warning: Data value '" + valueStr + "' larger than a byte (0xFF) found on data line: "
                                        + line + ". Storing truncated byte.");
                        value = value & 0xFF; // Store only the lower byte
                    }
                    // Store the byte value, formatted as two hex characters.
                    dataMemory.put(formatHex(address), String.format("%02X", value));
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading machine code file: " + filePath);
            e.printStackTrace();
        } catch (NumberFormatException e) {
            System.err.println("Error parsing hex value in file: " + filePath);
            e.printStackTrace();
        }

        if (basePC != -1) {
            this.pc = basePC; // Set PC to the start of the text segment
            System.out.println("Set initial PC to: " + formatHex(this.pc));
        } else {
            System.out.println("No instructions found in text segment. PC remains at 0x0.");
        }
        // Initialize instruction count for Knob 5 tracking (assuming this is part of a
        // larger class)
        this.instructionCount = 0; // Reset for new program run
        System.out.println("Parsing done. Loaded " + textSegment.size() + " instructions.");
    }

    // --- Printing Methods ---
    private void printRegisterFileState() {
        System.out.println("Register File State:");
        TreeMap<Integer, String> sortedRegs = new TreeMap<>();
        for (int i = 0; i < 32; i++) {
            sortedRegs.put(i, registerFile.get("x" + i));
        }
        for (Map.Entry<Integer, String> entry : sortedRegs.entrySet()) {
            System.out.printf("  x%d: %s ", entry.getKey(), entry.getValue());
            if ((entry.getKey() + 1) % 4 == 0)
                System.out.println(); // Newline every 4 registers
        }
        if (sortedRegs.size() % 4 != 0)
            System.out.println(); // Ensure final newline
    }

    private void printDataMemoryState() {
        System.out.println("\nData Memory State (Non-zero Bytes):");
        TreeMap<Long, String> sortedMemory = new TreeMap<>();
        for (Map.Entry<String, String> entry : dataMemory.entrySet()) {
            try {
                long addr = parseHex(entry.getKey());
                // Optionally filter out initial instruction bytes if desired
                // if (!textSegment.containsKey(entry.getKey())) { // Basic check
                sortedMemory.put(addr, entry.getValue());
                // }
            } catch (NumberFormatException e) {
                /* Ignore malformed keys */ }
        }
        if (sortedMemory.isEmpty()) {
            System.out.println("  <Empty or All Zeroes>");
        } else {
            // Print in groups (e.g., words) for readability
            long currentWordAddr = -1;
            StringBuilder wordLine = new StringBuilder();
            int bytesInLine = 0;

            for (Map.Entry<Long, String> entry : sortedMemory.entrySet()) {
                long addr = entry.getKey();
                String byteVal = entry.getValue();
                long wordAddr = addr & ~3L; // Get the word-aligned address

                if (wordAddr != currentWordAddr) {
                    // Print previous line if it exists
                    if (bytesInLine > 0) {
                        System.out.printf("  0x%08X: %s\n", currentWordAddr, wordLine.toString());
                    }
                    // Start new line
                    currentWordAddr = wordAddr;
                    wordLine = new StringBuilder();
                    // Add padding for unaligned start
                    for (long padAddr = wordAddr; padAddr < addr; padAddr++) {
                        wordLine.append("   "); // 3 spaces per missing byte
                        bytesInLine++;
                    }
                    wordLine.append(byteVal).append(" ");
                    bytesInLine++;
                } else {
                    // Pad missing bytes within the word
                    for (long padAddr = (addr - bytesInLine); padAddr < addr; padAddr++) {
                        wordLine.append("   ");
                    }
                    wordLine.append(byteVal).append(" ");
                    bytesInLine++;
                }
                if (bytesInLine == 4) {
                    System.out.printf("  0x%08X: %s\n", currentWordAddr, wordLine.toString());
                    wordLine = new StringBuilder();
                    bytesInLine = 0;
                    currentWordAddr = -1; // Reset for next word boundary
                }
            }
            // Print any remaining partial line
            if (bytesInLine > 0) {
                System.out.printf("  0x%08X: %s\n", currentWordAddr, wordLine.toString());
            }
        }
    }

    private void printFinalState() {
        System.out.println("\n--- Simulation Complete ---");
        System.out.println("Total Clock Cycles: " + clockCycle);
        // Calculate CPI if instructions were tracked properly
        // long executedInstructions = // Need a counter incremented in WB stage maybe?
        // System.out.printf("Executed Instructions: %d\n", executedInstructions);
        // System.out.printf("CPI: %.2f\n", (double) clockCycle / executedInstructions);
        if (pipeliningEnabled && printBPUEnabled) {
            System.out.println(bpu.toString()); // Final BPU stats
        }
        printRegisterFileState();
        printDataMemoryState();
    }

    // --- Main Method ---
    public static void main(String[] args) {
        String filePath = "output.mc"; // Default machine code file name
        boolean pipeliningEnabled = true; // Default: enabled
        boolean dataForwardingEnabled = true; // Default: enabled

        // Parse command-line arguments
        if (args.length > 0) {
            filePath = args[0];
            System.out.println("Using machine code file: " + filePath);
        } else {
            System.out.println("Using default machine code file: " + filePath);
        }

        if (args.length > 1) {
            pipeliningEnabled = Boolean.parseBoolean(args[1]);
            System.out.println("Pipelining enabled: " + pipeliningEnabled);
        }

        if (args.length > 2) {
            dataForwardingEnabled = Boolean.parseBoolean(args[2]);
            System.out.println("Data forwarding enabled: " + dataForwardingEnabled);
        }

        PipelinedCPU cpu = new PipelinedCPU();
        System.out.println(dataForwardingEnabled + " "+ pipeliningEnabled);
        // --- Set Knobs from arguments ---
        cpu.pipeliningEnabled = pipeliningEnabled;
        cpu.dataForwardingEnabled = dataForwardingEnabled;
        cpu.traceInstructionNum = -1;
        cpu.printRegistersEnabled = false;
        cpu.printPipelineRegsEnabled = true;
        // cpu.traceInstructionNum = 5;
        cpu.printBPUEnabled = true;

        // --- Parse and Run ---
        cpu.parseMachineCodeFromFile(filePath);
        cpu.run(); // Starts simulation
    }
}
