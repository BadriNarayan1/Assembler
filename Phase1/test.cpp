#include <iostream>
#include <fstream>
#include <sstream>
#include <cctype>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <iomanip>
#include <bitset>
#include <algorithm>
using namespace std;

// Define opcode, funct3, and funct7 mappings for RISC-V instructions
unordered_map<string, string> opcodeMap = {
    {"add", "0110011"}, {"sub", "0110011"}, {"and", "0110011"}, {"or", "0110011"},
    {"sll", "0110011"}, {"slt", "0110011"}, {"sra", "0110011"}, {"srl", "0110011"},
    {"xor", "0110011"}, {"mul", "0110011"}, {"div", "0110011"}, {"rem", "0110011"},
    {"addi", "0010011"}, {"andi", "0010011"}, {"ori", "0010011"}, {"jalr", "1100111"},
    {"lb", "0000011"}, {"ld", "0000011"}, {"lh", "0000011"}, {"lw", "0000011"},
    {"sb", "0100011"}, {"sw", "0100011"}, {"sd", "0100011"}, {"sh", "0100011"},
    {"beq", "1100011"}, {"bne", "1100011"}, {"bge", "1100011"}, {"blt", "1100011"},
    {"auipc", "0010111"}, {"lui", "0110111"}, {"jal", "1101111"}
};

unordered_map<string, string> funct3Map = {
    {"add", "000"}, {"sub", "000"}, {"and", "111"}, {"or", "110"},
    {"sll", "001"}, {"slt", "010"}, {"sra", "101"}, {"srl", "101"},
    {"xor", "100"}, {"mul", "000"}, {"div", "100"}, {"rem", "110"},
    {"addi", "000"}, {"andi", "111"}, {"ori", "110"}, {"jalr", "000"},
    {"lb", "000"}, {"ld", "011"}, {"lh", "001"}, {"lw", "010"},
    {"sb", "000"}, {"sw", "010"}, {"sd", "011"}, {"sh", "001"},
    {"beq", "000"}, {"bne", "001"}, {"bge", "101"}, {"blt", "100"}
};

unordered_map<string, string> funct7Map = {
    {"add", "0000000"}, {"sub", "0100000"}, {"sra", "0100000"}, {"srl", "0000000"},
    {"mul", "0000001"}, {"div", "0000001"}, {"rem", "0000001"}, {"sll", "0000000"},
};

unordered_set<string> iFormatInstructions = {"addi", "andi", "ori", "lb", "ld", "lh", "lw", "jalr"};

unordered_map<string, int> labelAddress;  // Stores label -> address mapping
unordered_map<long, long> dataSegment;     // Stores data segment memory
vector <pair<long, long>> sortedDataSegment; // Stores sorted data segment memory

bool isIFormatInstruction(const string& inst) {
    return iFormatInstructions.find(inst) != iFormatInstructions.end();
}

unordered_set<string> sFormatInstructions = {"sw", "sh", "sb", "sd"};

bool isSFormatInstruction(const string& inst) {
    return sFormatInstructions.find(inst) != sFormatInstructions.end();
}

unordered_set<string> sbFormatInstructions = {"beq", "bne", "bge", "blt"};

bool isSBFormatInstruction(const string& inst) {
    return sbFormatInstructions.find(inst) != sbFormatInstructions.end();
}


int computeOffset(string label, int currentPC) {
    if (labelAddress.find(label) == labelAddress.end()) {
        cerr << "Error: Undefined label " << label << endl;
        exit(1);
    }
    int labelAddr = labelAddress[label];
    return labelAddr - currentPC;
}

unordered_set<string> uFormatInstructions = {"auipc", "lui"};

bool isUFormatInstruction(const string& inst) {
    return uFormatInstructions.find(inst) != uFormatInstructions.end();
}

unordered_set<string> ujFormatInstructions = {"jal"};

bool isUJFormatInstruction(const string& inst) {
    return ujFormatInstructions.find(inst) != ujFormatInstructions.end();
}

string registerToBinary(string reg) {
    int regNum = stoi(reg.substr(1));
    return bitset<5>(regNum).to_string();
}

template <size_t N>
bitset<N> parseImmediate(const std::string& imm) {
    int value = 0; // Default value

    if (imm.size() > 2 && imm[0] == '0') {
        if (imm[1] == 'x' || imm[1] == 'X') {
            // Hexadecimal (e.g., 0xA5)
            value = stoi(imm.substr(2), nullptr, 16);
        } else if (imm[1] == 'b' || imm[1] == 'B') {
            // Binary (e.g., 0b1010)
            value = stoi(imm.substr(2), nullptr, 2);
        } else {
            throw invalid_argument("Invalid immediate format!");
        }
    } else {
        // Decimal (default)
        value = stoi(imm);
    }

    return bitset<N>(value);
}

string parseRFormat(string inst, string rd, string rs1, string rs2) {
    return funct7Map[inst] + registerToBinary(rs2) + registerToBinary(rs1) +
           funct3Map[inst] + registerToBinary(rd) + opcodeMap[inst];
}

string parseIFormat(string inst, string rd, string rs1, bitset<12> imm) {
    return imm.to_string() + registerToBinary(rs1) + funct3Map[inst] +
           registerToBinary(rd) + opcodeMap[inst];
}

string parseSFormat(string inst, string rs1, string rs2, bitset<12> imm_bin) {
    string opcode = opcodeMap[inst];
    string funct3 = funct3Map[inst];

    bitset<5> rs1_bin(registerToBinary(rs1));
    bitset<5> rs2_bin(registerToBinary(rs2));

    // S-format: imm[11:5] | rs2 | rs1 | funct3 | imm[4:0] | opcode
    // cout << imm_bin.to_string().substr(0, 7) << endl;
    // cout << imm_bin.to_string().substr(7) << endl;
    string machineCode = imm_bin.to_string().substr(0, 7) +
                         rs2_bin.to_string() +
                         rs1_bin.to_string() +
                         funct3 +
                         imm_bin.to_string().substr(7) +
                         opcode;

    return machineCode;
}

string parseSBFormat(string inst, string rs1, string rs2, bitset<13> offset) {
    string opcode = opcodeMap[inst];  // SB-format opcode
    string funct3 = funct3Map[inst];  // Lookup funct3

    bitset<5> rs1_bin(registerToBinary(rs1));
    bitset<5> rs2_bin(registerToBinary(rs2));

    // SB-format: imm[12] | imm[10:5] | rs2 | rs1 | funct3 | imm[4:1] | imm[11] | opcode
    string machineCode = offset.to_string().substr(0, 1) +    // imm[12]
                         offset.to_string().substr(2, 6) +    // imm[10:5]
                         rs2_bin.to_string() +
                         rs1_bin.to_string() +
                         funct3 +
                         offset.to_string().substr(8, 4) +    // imm[4:1]
                         offset.to_string().substr(1, 1) +    // imm[11]
                         opcode;

    return machineCode;
}


string parseUFormat(string inst, string rd, bitset<20> imm_bin) {
    string opcode = opcodeMap[inst];  // LUI = 0110111, AUIPC = 0010111
    bitset<5> rd_bin(registerToBinary(rd));

    // U-Format: imm[31:12] | rd | opcode
    string machineCode = imm_bin.to_string() + rd_bin.to_string() + opcode;

    return machineCode;
}

string parseUJFormat(string inst, string rd, bitset<21> imm_bin) {
    string opcode = opcodeMap[inst];  // Get the opcode for `jal`
    bitset<5> rd_bin(registerToBinary(rd));

    // UJ-format: imm[20] | imm[10:1] | imm[11] | imm[19:12] | rd | opcode
    string machineCode = imm_bin.to_string().substr(0, 1) +    // imm[20] (MSB)
                         imm_bin.to_string().substr(10, 10) +  // imm[10:1]
                         imm_bin.to_string().substr(9, 1) +    // imm[11]
                         imm_bin.to_string().substr(1, 8) +    // imm[19:12]
                         rd_bin.to_string() +
                         opcode;

    return machineCode;
}


string formatBinaryInstruction(string opcode, string funct3, string funct7, string rd, string rs1, string rs2, string imm) {
    stringstream ss;

    // Print opcode, funct3, funct7, and register fields in binary format
    ss << opcode << "-";
    if (!funct3.empty()) {
        ss << funct3 << "-";
    } else {
        ss << "NULL-";
    }

    // For R-format instructions (funct7 is present)
    if (!funct7.empty()) {
        ss << funct7 << "-";
    } else {
        ss << "NULL-";
    }

    if (!rd.empty()) {
        ss << rd << "-";
    }
    else {
        ss << "NULL-";
    }

    if (!rs1.empty()) {
        ss << rs1 << "-";
    }
    else {
        ss << "NULL-";
    }

    if (!rs2.empty()) {
        ss << rs2 << "-";
    }
    else {
        ss << "NULL-";
    }

    if (!imm.empty()) {
        ss << imm;
    } else {
        ss << "NULL";
    }

    return ss.str();
}


void firstPass(istringstream &inFile) {
    string line, label, directive;
    int address = 0;                // Instruction address (code segment)
    int dataAddress = 0x10000000;    // Data segment starts at 0x10000000
    bool inTextSegment = true;       // Flag to track whether we are in .text


    while (getline(inFile, line)) {
        istringstream iss(line);
        string firstWord;
        iss >> firstWord;

        // Detect segment change
        if (firstWord == ".text") {
            inTextSegment = true;
            continue;
        } else if (firstWord == ".data") {
            inTextSegment = false;
            continue;
        }

        // Handle .text segment (Instructions & Labels)
        if (inTextSegment) {
            if (firstWord.back() == ':') {
                label = firstWord.substr(0, firstWord.size() - 1);
                labelAddress[label] = address;
                iss >> firstWord;
                if (opcodeMap.find(firstWord) != opcodeMap.end()) {
                    address += 4;
                }
            } else {
                address += 4;
            }
        }

        // Handle .data segment (Directives)
        else {
            istringstream dataStream(line);
            string varName, directive, value;
            dataStream >> varName >> directive;

            if (directive == ".byte") {
                while (dataStream >> value) {
                    dataSegment[dataAddress] = stoi(value);
                    dataAddress += 1;
                }
            }
            else if (directive == ".half") {
                while (dataStream >> value) {
                    bitset<16> bits = parseImmediate<16>(value); // Convert to 16-bit binary
                    for (int i = 0; i < 2; ++i) {
                        dataSegment[dataAddress + i] = (bits.to_ulong() >> (8 * i)) & 0xFF;
                    }
                    dataAddress += 2;
                }
            }
            else if (directive == ".word") {
                while (dataStream >> value) {
                    bitset<32> bits = parseImmediate<32>(value); // Convert to 32-bit binary
                    for (int i = 0; i < 4; ++i) {
                        dataSegment[dataAddress + i] = (bits.to_ulong() >> (8 * i)) & 0xFF;
                    }
                    dataAddress += 4;
                }
            }
            else if (directive == ".dword") {
                while (dataStream >> value) {
                    bitset<64> bits = parseImmediate<64>(value); // Convert to 64-bit binary
                    for (int i = 0; i < 8; ++i) {
                        dataSegment[dataAddress + i] = (bits.to_ullong() >> (8 * i)) & 0xFF;
                    }
                    dataAddress += 8;
                }
            }
            else if (directive == ".asciz") {
                string str;
                getline(dataStream, str);
                int size = str.size();
                str = str.substr(2, size - 3); // Remove quotes
                for (char c : str) {
                    dataSegment[dataAddress] = c;
                    dataAddress += 1;
                }
                dataSegment[dataAddress] = 0; // Null terminator
                dataAddress += 1;
            }
        }
    }
}

void assemble() {
    string helper, inputCode;
    while (getline(cin, helper) && !helper.empty()) {
        inputCode += helper + "\n";
    }
    istringstream inFile(inputCode);
    istringstream inFile2(inputCode);

    // ifstream inFile(inputFile);
    // ofstream cout(outputFile);
    // ifstream inFile2(inputFile);
    firstPass(inFile2);
    string line;
    int address = 0;
    bool inTextSegment = true;


    while (getline(inFile, line)) {
        istringstream iss(line);
        istringstream iss2(line);
        // if (line.empty() || all_of(line.begin(), line.end(), [](unsigned char c){ return isspace(c); })) continue;
        string firstWord;
        iss2 >> firstWord;
        // Detect segment change
        if (firstWord == ".text") {
            inTextSegment = true;
            continue;  // Skip this line
        } else if (firstWord == ".data") {
            inTextSegment = false;
            continue;  // Skip this line
        }
        if (inTextSegment == false) {
            continue;
        }
        string inst, rd, rs1, rs2;
        string formatedInstruction;
        string offsetOrLabel;
        string imm;
        iss >> inst;
        if (inst.back() == ':') {
            iss >> inst;
        }
        if (opcodeMap.find(inst) != opcodeMap.end()) {
            if (funct3Map.find(inst) != funct3Map.end()) {
                if (funct7Map.find(inst) != funct7Map.end()) {
                    // R-Type instruction
                    iss >> rd >> rs1 >> rs2;
                    bitset<32> machineCode(parseRFormat(inst, rd, rs1, rs2));
                    formatedInstruction = formatBinaryInstruction(
                        opcodeMap[inst], funct3Map[inst], funct7Map[inst],
                        registerToBinary(rd), registerToBinary(rs1), registerToBinary(rs2), ""
                    );
                    cout << "0x" << hex << address << " 0x"
                            << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                            << " , " << line << " # " << formatedInstruction << endl;
                }
                else {
                    if (isSFormatInstruction(inst)) {
                        // S-Type instruction
                        char temp;
                        if (line.find('(') != string::npos) {
                            // Parsing "sw rs2, imm(rs1)" format
                            string immWithReg;
                            iss >> rs2 >> immWithReg;

                            size_t pos = immWithReg.find('(');
                            imm = immWithReg.substr(0, pos);  // Extract immediate
                            rs1 = immWithReg.substr(pos + 1, immWithReg.size() - pos - 2);  // Extract rs1 (remove brackets)
                        } else {
                            // Parsing "sw rs2 imm rs1" format
                            iss >> rs2 >> imm >> rs1;
                        }
                        bitset<12> immediate = parseImmediate<12>(imm);
                        bitset<32> machineCode(parseSFormat(inst, rs1, rs2, immediate));

                        formatedInstruction = formatBinaryInstruction(
                            opcodeMap[inst], funct3Map[inst], "", "",
                            registerToBinary(rs1), registerToBinary(rs2),
                            immediate.to_string()
                        );

                        cout << "0x" << hex << address << " 0x"
                                << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                                << " , " << line << " # " << formatedInstruction << endl;
                    }
                    else if (isSBFormatInstruction(inst)) {
                        // SB-Type instruction (BEQ, BNE, BLT, BGE)
                        iss >> rs1 >> rs2 >> offsetOrLabel;
                        bitset<13> offset;

                        if (isdigit(offsetOrLabel[0]) || offsetOrLabel[0] == '-' || offsetOrLabel[0] == '+') {
                            offset = parseImmediate<13>(offsetOrLabel);  // Convert directly if it's a number
                        } else {
                            offset = bitset<13>(computeOffset(offsetOrLabel, address));  // Convert label to PC-relative offset
                        }

                        offset &= ~bitset<13>(3);  // Mask the lower 2 bits (branches must be aligned)

                        bitset<32> machineCode(parseSBFormat(inst, rs1, rs2, offset));

                        formatedInstruction = formatBinaryInstruction(
                            opcodeMap[inst], funct3Map[inst], "", "",
                            registerToBinary(rs1), registerToBinary(rs2), offset.to_string()
                        );

                        cout << "0x" << hex << address << " 0x"
                                << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                                << " , " << line << " # " << formatedInstruction << endl;
                    }
                    else if (isIFormatInstruction(inst)) {
                        // I-Type instruction
                        if (inst == "lw" || inst == "lb" || inst == "ld" || inst == "lh") {
                            // Support both "lw rd, imm(rs1)" and "lw rd imm rs1"
                            string immWithReg;
                            iss >> rd >> immWithReg;

                            if (immWithReg.find('(') != string::npos) {
                                // Handling "lw rd, imm(rs1)" format
                                size_t pos = immWithReg.find('(');
                                imm = immWithReg.substr(0, pos);  // Extract immediate
                                rs1 = immWithReg.substr(pos + 1, immWithReg.size() - pos - 2);  // Extract rs1 (remove brackets)
                            } else {
                                // Handling "lw rd imm rs1" format
                                imm = immWithReg;  // immWithReg contains imm
                                iss >> rs1;        // Read rs1 separately
                            }
                        }
                        else if (inst == "jalr") {
                            // Support both "jalr rd, imm(rs1)" and "jalr rd, rs1, imm"
                            string immWithReg;
                            iss >> rd >> immWithReg;

                            if (immWithReg.find('(') != string::npos) {
                                // Handling "jalr rd, imm(rs1)" format
                                size_t pos = immWithReg.find('(');
                                imm = immWithReg.substr(0, pos);  // Extract immediate
                                rs1 = immWithReg.substr(pos + 1, immWithReg.size() - pos - 2);  // Extract rs1 (remove brackets)
                            } else {
                                // Handling "jalr rd, rs1, imm" format
                                rs1 = immWithReg;  // immWithReg contains rs1
                                iss >> imm;        // Read imm separately
                            }
                        }
                        else {
                            // Standard I-Type format: inst rd, rs1, imm
                            iss >> rd >> rs1 >> imm;
                        }
                        bitset<12> immediate = (parseImmediate<12>(imm));
                        bitset<32> machineCode(parseIFormat(inst, rd, rs1, immediate));

                        formatedInstruction = formatBinaryInstruction(
                            opcodeMap[inst], funct3Map[inst], "",
                            registerToBinary(rd), registerToBinary(rs1), "",
                            immediate.to_string()
                        );

                        cout << "0x" << hex << address << " 0x"
                                << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                                << " , " << line << " # " << formatedInstruction << endl;
                    }
                }
            }
            else if (isUFormatInstruction(inst)) {
                // U-Type instruction
                iss >> rd >> imm;
                bitset<20> immediate = (parseImmediate<20>(imm));
                bitset<32> machineCode(parseUFormat(inst, rd, immediate));

                formatedInstruction = formatBinaryInstruction(
                    opcodeMap[inst], "", "", registerToBinary(rd), "", "", immediate.to_string()
                );

                cout << "0x" << hex << address << " 0x"
                        << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                        << " , " << line << " # " << formatedInstruction << endl;
            }
            else if (isUJFormatInstruction(inst)) {
                // UJ-Type instruction (JAL)
                iss >> rd >> offsetOrLabel;
                bitset<21> offset;
                if (isdigit(offsetOrLabel[0]) || offsetOrLabel[0] == '-' || offsetOrLabel[0] == '+') {
                    offset = parseImmediate<21>(offsetOrLabel);  // Convert directly if it's a number
                } else {
                    offset = bitset<21>(computeOffset(offsetOrLabel, address));  // Convert label to PC-relative offset
                }

                offset &= ~bitset<21>(3);  // Mask the lower 2 bits
                bitset<32> machineCode(parseUJFormat(inst, rd, offset));

                formatedInstruction = formatBinaryInstruction(
                    opcodeMap[inst], "", "", registerToBinary(rd), "", "", (offset).to_string()
                );

                cout << "0x" << hex << address << " 0x"
                        << setw(8) << setfill('0') << stoul(machineCode.to_string(), nullptr, 2)
                        << " , " << line << " # " << formatedInstruction << endl;
            }

            address += 4;  // Move to next instruction
        }
    }
    cout << "0x" << hex << address << " 0xdeadbeef" << " , " << "ends" << endl;
    sortedDataSegment = vector<pair<long, long>>(dataSegment.begin(), dataSegment.end());
    sort(sortedDataSegment.begin(), sortedDataSegment.end());
    for (const auto& [key, value] : sortedDataSegment) {
        cout << "0x" << hex << key << " 0x" << setw(2) << setfill('0') << value << endl;
    }

    // inFile.close();
    // cout.close();
}

int main() {
    // assemble("/Users/mitul/Desktop/iit ropar/ComputerArchitecture-CS204/CS204_Project_1_Group_17/input.asm", "/Users/mitul/Desktop/iit ropar/ComputerArchitecture-CS204/CS204_Project_1_Group_17/output.mc");
    assemble();
    // cout << "Assembly translation complete. Check output.mc" << endl;
    return 0;
}