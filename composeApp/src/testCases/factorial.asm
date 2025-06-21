.data
n: .word 6
.text
lui x1 0x10000
lw x3 0 x1
jal x1 factorial
jal x0 end
factorial:
    addi x2 x2 -8
    sw x3 0 x2 #store n
    sw x1 4 x2 #store return address
    addi x5 x0 2 #base case
    blt x3 x5 baseCase
    addi x3 x3 -1
    jal x1 factorial
    lw x3 0 x2 #load the previous n
    mul x10 x10 x3
    lw x1 4 x2
    addi x2 x2 8
    jalr x0 x1 0
baseCase:
    lw x1 4 x2
    addi x10 x0 1 #base case result in x10
    addi x2 x2 8
    jalr x0 x1 0
end: