.data
n: .word 10
.text
main:
lui x31 0x10000
lw x10 0 x31
jal x1 fib
jal x0 main_exit
fib:
addi x2 x2 -12  #make space for storing, return adress , followed by n and then the value of fib_n-1
sw x10 4 x2
sw x1 8 x2
addi x5 x0 2 #base casee for n=2 we want to caclulate fib(0) and fib(1)
blt x10 x5 base_case
addi x10 x10 -1
jal x1 fib
sw x10 0 x2 #result of fib_n-1
lw x10 4 x2 #load the callee n
addi x10 x10 -2  #for fib_n-2
addi x2 x2 -4  #VERY IMPPP-> to make space for returining to the callee after fib_n-2 is calculated
sw x1 0 x2  #redundant hai par consistency ke liye daaldo
jal x1 fib #make the call for fib_n-2
addi x2 x2 4  #pop the address for the returning of fib_n-2
lw x6 0 x2 #the value of fib(n-1)
add x10 x10 x6  #fib(n-1)+fib(n-2)
lw x1 8 x2  #move to the previous return address
addi x2 x2 12 #move the stack pointer to previous n
jalr x0 x1 0 #repeat for previous n
base_case:
lw x1 8 x2
addi x2 x2 12
jalr x0 x1 0
main_exit: