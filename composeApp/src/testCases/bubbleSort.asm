.data
arr1: .word 10 9 8 7 6 5 4 3 2 1
.text
lui x1 0x10000
addi x2 x0 0 #the current pointer
addi x3 x0 10 #the size of the array
addi x30 x0 1
addi x31 x0 4
main_loop:
    bge x2 x3 main_end
    addi x4 x0 0
    addi x5 x0 1 # numbere of times we will iterate with the next element comparison
    it_loop:
        bge x5 x3 it_end
        addi x6 x5 -1 # the current index
        mul x7 x31 x6 #offset
        add x8 x1 x7 #current element address
        addi x9 x8 4 #next element
        lw x10 0 x8 #current
        lw x11 0 x9 #next
        bge x11 x10 swap_end
        swap:
            sw x11 0 x8
            sw x10 0 x9
        swap_end:
        addi x5 x5 1
        beq x0 x0 it_loop
    it_end:
    addi x2 x2 1
    beq x0 x0 main_loop
main_end: