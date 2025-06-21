package org.example.project

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.navigation.NavController
import androidx.navigation.NavHost
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

fun main() = application {
    val navController = rememberNavController()
    val executionViewModel = remember { ExecutionViewModel() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Assembler",
    ) {
        NavHost(navController = navController, startDestination = "basicUi") {
            composable("basicUi") {
                basicUi(onExecutionClicked = { output ->
                    executionViewModel.parseMachineCode(output)
                    navController.navigate("execution")
                }) { input->
                    runAssembler(input)
                }
            }
            composable("execution") {
                RiscVSimulatorUI(executionViewModel)
            }
        }
//        basicUi { runAssemblerExecutable() }
    }
}


fun runAssembler(inputText: String): String {
    return try {
        // Start the C++ executable as a process
        val process = ProcessBuilder("src/bin/test")
            .redirectErrorStream(true)
            .start()
//        val process = ProcessBuilder("/Users/mitul/Desktop/iit ropar/ComputerArchitecture-CS204/CS204_Project_1_Group_17/test")
//            .redirectErrorStream(true)
//            .start()

        // Write input to the process
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        writer.write(inputText)
        writer.newLine()
        writer.flush()
        writer.close()

        // Capture output from the process
        val output = process.inputStream.bufferedReader().readText()

        // Wait for process to finish
        process.waitFor()


        val pcToInstruction = hashMapOf<String, String>()
        val memoryMap = hashMapOf<String, String>()

        output // Return the assembler output
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}

@Preview
@Composable
fun basicUi(onExecutionClicked: (String) -> Unit, onClick: (String) -> String) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val inputScrollState = rememberScrollState()
    val outputScrollState = rememberScrollState()
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.verticalScroll(inputScrollState).height(200.dp)
        ) {
            TextField(
                label = {
                    Text("Enter Assembly Code")
                },
                value = input,
                onValueChange = {input = it})
        }
        Button(onClick = { output = onClick(input)}) {
            Text("Assemble")
        }

        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp).verticalScroll(outputScrollState),
            elevation = 4.dp
        ) {
            Column {
                Text(text = "Output: ", style = MaterialTheme.typography.h6)
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = output)
            }
        }

        Button(onClick = { onExecutionClicked(output) }) {
            Text("Execute")
        }


    }
}

