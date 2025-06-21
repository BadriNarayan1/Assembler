package org.example.project

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.skia.Canvas
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin


@Composable
@Preview
fun RiscVSimulatorUI(executionViewModel: ExecutionViewModel) {
    var registerWindowOpened by remember { mutableStateOf(false) }
    var memoryWindowOpened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (registerWindowOpened) {
        Window(
            onCloseRequest = { registerWindowOpened = false },
            title = "Register File"
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Register File",
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                //  Sort the map by register number
                executionViewModel.registerFile
                    .toSortedMap(compareBy { it.removePrefix("x").toInt() })
                    .forEach { (reg, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = reg, style = MaterialTheme.typography.body1)
                            Text(text = value, style = MaterialTheme.typography.body1)
                        }
                    }
            }
        }
    }


    if (memoryWindowOpened) {
        Window(
            onCloseRequest = { memoryWindowOpened = false },
            title = "Memory"
        ) {
            var searchAddress by remember { mutableStateOf("") }
            val lazyListState = rememberLazyListState()

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Memory View (0x00000000 - 0x7FFFFFFC)",
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Search Block
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    TextField(
                        value = searchAddress,
                        onValueChange = { searchAddress = it },
                        label = { Text("Jump to Address (e.g., 0x00000010)") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val addressInt = searchAddress.removePrefix("0x").toLongOrNull(16)
                        if (addressInt != null) {
                            val index = (addressInt).toInt()
                            // Scroll to the index in the LazyColumn
                            scope.launch { lazyListState.animateScrollToItem(index) }
                        }
                    }) {
                        Text("Jump")
                    }
                }

                // LazyColumn with Vertical Scrollbar
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = lazyListState) {
                        // Approx 0.5 GB range if visualized, but since LazyColumn is virtualized, it's fine
                        items(count = (0x7FFFFFFC + 1).toInt()) { index ->
                            val addressHex = String.format("0x%08X", index)
                            val value = executionViewModel.memoryMap[addressHex] ?: "00"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(addressHex)
                                Text(value)
                            }
                        }
                    }

                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(lazyListState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }


    val verticalScrollState = rememberScrollState(initial = 0)
    val horizontalScrollState = rememberScrollState(initial = 1000)
    Column (modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly){
                Text("Current Instruction: ${executionViewModel.ir.value}")
                Text("Clock: ${executionViewModel.clock.value}")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = {registerWindowOpened = true}) {
                    Text("Register Window")
                }
                Button(onClick = {memoryWindowOpened = true}) {
                    Text("Memory Window")
                }
                Button(onClick = {
                    if (executionViewModel.ir.value != "0xdeadbeef") {
                        when (executionViewModel.currentStage.value) {
                            PipelineStage.FETCH -> {
                                executionViewModel.fetch()
                                executionViewModel.currentStage.value = PipelineStage.DECODE
                            }
                            PipelineStage.DECODE -> {
                                executionViewModel.decode()
                                executionViewModel.currentStage.value = PipelineStage.EXECUTE
                            }
                            PipelineStage.EXECUTE -> {
                                executionViewModel.execute()
                                executionViewModel.currentStage.value = PipelineStage.MEMORY
                            }
                            PipelineStage.MEMORY -> {
                                executionViewModel.memoryAccess()
                                executionViewModel.currentStage.value = PipelineStage.WRITEBACK
                            }
                            PipelineStage.WRITEBACK -> {
                                executionViewModel.writeBack()
                                executionViewModel.currentStage.value = PipelineStage.FETCH // Move to next instruction
                            }
                        }
                    }
                }) {
                    Text("Next Step (${executionViewModel.currentStage.value})")
                }

                Button(onClick = {
                    // Keep executing the pipeline until deadbeef is reached
                    while (executionViewModel.ir.value != "0xdeadbeef") {
                        when (executionViewModel.currentStage.value) {
                            PipelineStage.FETCH -> {
                                executionViewModel.fetch()
                                executionViewModel.currentStage.value = PipelineStage.DECODE
                            }
                            PipelineStage.DECODE -> {
                                executionViewModel.decode()
                                executionViewModel.currentStage.value = PipelineStage.EXECUTE
                            }
                            PipelineStage.EXECUTE -> {
                                executionViewModel.execute()
                                executionViewModel.currentStage.value = PipelineStage.MEMORY
                            }
                            PipelineStage.MEMORY -> {
                                executionViewModel.memoryAccess()
                                executionViewModel.currentStage.value = PipelineStage.WRITEBACK
                            }
                            PipelineStage.WRITEBACK -> {
                                executionViewModel.writeBack()
                                executionViewModel.currentStage.value = PipelineStage.FETCH
                            }
                        }
                    }
                }) {
                    Text("Run All")
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.verticalScroll(verticalScrollState).horizontalScroll(horizontalScrollState).border(
                    width = 4.dp,
                    brush = SolidColor(Color.DarkGray),
                    shape = RoundedCornerShape(16.dp))
            ) {
                val screenHeight = 2000f
                val screenWidth = 1500f
                val textMeasurer = rememberTextMeasurer()

                Canvas(modifier = Modifier.size(screenWidth.dp, screenHeight.dp)) {
                    val nativeCanvas: Canvas = drawContext.canvas.nativeCanvas
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val padding1 = 100f
                    val padding2 = 70f
                    val padding3 = 150f


                    val registerWidth = 340f
                    val registerHeight = 340f
                    val raWidth = 340f
                    val raHeight = 80f
                    val topWidthMuxB = 340f
                    val bottomWidthMuxB = 200f
                    val heightMuxB = raHeight + 10f
                    val topWidthAlu = 500f
                    val bottomWidthAlu = 340f
                    val heightAlu = heightMuxB + 50f

                    val pcX = canvasWidth / 2 - raWidth - 30f
                    val pcY = 500f

                    val muxIncCenter = Offset(canvasWidth / 2 + topWidthMuxB / 2, pcY)

                    val centerForMuxpc = Offset(pcX + registerWidth / 2, pcY - padding1 - heightMuxB)
                    drawTrapeziumWithText(
                        centerForMuxpc,
                        topWidthMuxB,
                        bottomWidthMuxB,
                        heightMuxB,
                        "MUXPC",
                        textMeasurer
                    )
                    val startMuxToPc = Offset(centerForMuxpc.x, centerForMuxpc.y + heightMuxB)
                    val endMuxToPc = Offset(centerForMuxpc.x, pcY)
                    drawLine(
                        color = Color.Black,
                        start = startMuxToPc,
                        end = endMuxToPc
                    )
                    drawArrow(startMuxToPc, endMuxToPc)

                    val startRAToMux = Offset(centerForMuxpc.x - 50f, centerForMuxpc.y - 50f)
                    val endRAToMux = Offset(centerForMuxpc.x - 50f, centerForMuxpc.y)
                    drawLine(
                        color = Color.Black,
                        start = startRAToMux,
                        end = endRAToMux
                    )
                    drawArrow(startRAToMux, endRAToMux)

                    val raText = "RA (${executionViewModel.ra.value ?: "null"})"
                    val raTextLayout = textMeasurer.measure(
                        text = raText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

                    drawText(
                        raTextLayout,
                        topLeft = Offset(
                            startRAToMux.x - raTextLayout.size.width / 2,  // 5f padding left of the line
                            startRAToMux.y - raTextLayout.size.height - 5f   // Vertically center
                        )
                    )

                    drawLabeledRect(
                        topLeft = Offset(pcX, pcY),
                        size = Size(raWidth, raHeight),
                        label = "PC",
                        textMeasurer
                    )

                    drawTrapeziumWithText(
                        center = muxIncCenter,
                        topWidth = topWidthMuxB,
                        bottomWidth = bottomWidthMuxB,
                        height = heightMuxB,
                        textMeasurer = textMeasurer,
                        text = "MUXINC"
                    )

                    val startFTomux = Offset(muxIncCenter.x - 50f, muxIncCenter.y - 50f)
                    val endFToMux = Offset(muxIncCenter.x - 50f, muxIncCenter.y)
                    drawLine(
                        color = Color.Black,
                        start = startFTomux,
                        end = endFToMux
                    )
                    drawArrow(startFTomux, endFToMux)

                    val fourText = "4"
                    val fourTextLayout = textMeasurer.measure(
                        text = fourText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Position the "4" left of the start point
                    drawText(
                        fourTextLayout,
                        topLeft = Offset(
                            startFTomux.x - fourTextLayout.size.width / 2,  // 5f padding
                            startFTomux.y - fourTextLayout.size.height - 5f // Center vertically
                        )
                    )

                    val startImmToMux = Offset(muxIncCenter.x + 50f, muxIncCenter.y - 50f)
                    val endImmToMux = Offset(muxIncCenter.x + 50f, muxIncCenter.y)
                    drawLine(
                        color = Color.Black,
                        start = startImmToMux,
                        end = endImmToMux
                    )
                    drawArrow(startImmToMux, endImmToMux)

                    val immText = executionViewModel.immMuxInr.value ?: "0x00000000"
                    val immTextLayout = textMeasurer.measure(
                        text = immText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Position the text slightly left of the start point
                    drawText(
                        immTextLayout,
                        topLeft = Offset(
                            startImmToMux.x - 50f,  // 5f padding
                            startImmToMux.y - immTextLayout.size.height - 5f// Vertically centered
                        )
                    )

                    val centerForAdder = Offset(canvasWidth / 2, pcY + raHeight + padding1)
                    drawTrapeziumWithText(
                        center = centerForAdder,
                        topWidthAlu,
                        bottomWidthAlu,
                        heightAlu,
                        "ADD",
                        textMeasurer
                    )

                    val startAddToValue = Offset(centerForAdder.x, centerForAdder.y + heightAlu)
                    val endAddToValue = Offset(centerForAdder.x, centerForAdder.y + heightAlu + 30f)
                    drawLine(
                        color = Color.Black,
                        start = startAddToValue,
                        end = endAddToValue
                    )
                    drawArrow(startAddToValue, endAddToValue)

                    val nextPcText = executionViewModel.pcMuxPc.value
                    val nextPcTextLayout = textMeasurer.measure(
                        text = nextPcText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

                    drawText(
                        nextPcTextLayout,
                        topLeft = Offset(
                            endAddToValue.x - nextPcTextLayout.size.width / 2,  // 5f padding to the right of the arrow end
                            endAddToValue.y + 5f // Center vertically with the line
                        )
                    )

                    drawLine(
                        color = Color.Black,
                        start = Offset(endAddToValue.x, endAddToValue.y - 10f),
                        end = Offset(muxIncCenter.x + topWidthMuxB / 2 + 20f, endAddToValue.y - 10f)
                    )

                    drawLine(
                        color = Color.Black,
                        start = Offset(muxIncCenter.x + topWidthMuxB / 2 + 20f, endAddToValue.y - 10f),
                        end = Offset(muxIncCenter.x + topWidthMuxB / 2 + 20f, centerForMuxpc.y - 20f)
                    )

                    drawLine(color = Color.Black,
                        start = Offset(muxIncCenter.x + topWidthMuxB / 2 + 20f, centerForMuxpc.y - 20f),
                        end = Offset(centerForMuxpc.x + 50f , centerForMuxpc.y - 20f)
                    )
                    val startAddToMux = Offset(centerForMuxpc.x + 50f, centerForMuxpc.y - 20f)
                    val endAddToMuc = Offset(centerForMuxpc.x + 50f, centerForMuxpc.y)
                    drawLine(
                        color = Color.Black,
                        start = startAddToMux,
                        end = endAddToMuc
                    )
                    drawArrow(startAddToMux, endAddToMuc)


                    val startPcToAdd = Offset(pcX + raWidth / 2, pcY + raHeight)
                    val endPcToAdd = Offset(pcX + raWidth / 2, centerForAdder.y)
                    drawLine(
                        color = Color.Black,
                        start = startPcToAdd,
                        end = endPcToAdd
                    )
                    drawArrow(startPcToAdd, endPcToAdd)

                    val startMuxIncToAdd = Offset(muxIncCenter.x, muxIncCenter.y + heightMuxB)
                    val endMuxIncToAdd = Offset(muxIncCenter.x, centerForAdder.y)
                    drawLine(
                        color = Color.Black,
                        start = startMuxIncToAdd,
                        end = endMuxIncToAdd
                    )
                    drawArrow(startMuxIncToAdd, endMuxIncToAdd)

                    val pcTempX = centerForAdder.x - topWidthAlu / 2 - raWidth - 80f
                    val pcTempY = centerForAdder.y
                    val endPcToPcTemp = Offset(pcTempX + raWidth / 2, pcTempY)
                    drawLine(
                        color = Color.Black,
                        startPcToAdd,
                        end = endPcToPcTemp
                    )

                    drawArrow(startPcToAdd, endPcToPcTemp)
                    drawLabeledRect(
                        topLeft = Offset(pcTempX, pcTempY),
                        size = Size(raWidth, raHeight),
                        label = "PC-TEMP",
                        textMeasurer
                    )

                    val startTempToValue = Offset(pcTempX + raWidth / 2, pcTempY + raHeight)
                    val endTempToValue = Offset(pcTempX + raWidth / 2, pcTempY + raHeight + 20f)
                    drawLine(
                        color = Color.Black,
                        start = startTempToValue,
                        end = endTempToValue
                    )
                    drawArrow(startTempToValue, endTempToValue)

                    val pcTempText = executionViewModel.pcTemp.value ?: "0x00000000"
                    val pcTempTextLayout = textMeasurer.measure(
                        text = pcTempText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

                    drawText(
                        pcTempTextLayout,
                        topLeft = Offset(
                            endTempToValue.x  - pcTempTextLayout.size.width / 2,  // 5f padding to the right of the end point
                            endTempToValue.y + 5f  // Vertically centered
                        )
                    )

                    val registerX = (canvasWidth / 2) - (registerWidth / 2)
                    val registerY = 1000f

                    drawLabeledRect(
                        topLeft = Offset(registerX, registerY),
                        size = Size(registerWidth, registerHeight),
                        label = "RegisterFile",
                        textMeasurer = textMeasurer
                    )

                    val startRdToRegister = Offset(registerX + registerWidth + 30f, registerY + registerHeight / 2)
                    val endRdToRegister = Offset(registerX + registerWidth, registerY + registerHeight / 2)
                    drawLine(
                        color = Color.Black,
                        start = startRdToRegister,
                        end = endRdToRegister
                    )

                    drawArrow(startRdToRegister, endRdToRegister)

                    val rdText = executionViewModel.rd.value ?: "Rd"
                    val rdTextLayout = textMeasurer.measure(
                        text = rdText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Place "rd" text slightly to the left of start point and vertically centered
                    drawText(
                        rdTextLayout,
                        topLeft = Offset(
                            startRdToRegister.x + 5f,
                            startRdToRegister.y - rdTextLayout.size.height / 2
                        )
                    )

                    val startAddressA = Offset(registerX - 20f, registerY + 50f)
                    val endAddressA = Offset(registerX, registerY + 50f)
                    drawLine(
                        color = Color.Black,
                        start = startAddressA,
                        end = endAddressA
                    )
                    drawArrow(
                        startAddressA,
                        endAddressA
                    )

// Draw "5-bit Address" text at the start of the line
                    val addressAText = executionViewModel.rs1.value ?: "No Address"
                    val addressATextLayout = textMeasurer.measure(
                        text = addressAText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Position the text slightly to the left of the start point
                    drawText(
                        addressATextLayout,
                        topLeft = Offset(
                            startAddressA.x - addressATextLayout.size.width - 5f, // 5f padding from the start
                            startAddressA.y - addressATextLayout.size.height / 2  // Center vertically with the line
                        )
                    )

                    val startAddressB = Offset(startAddressA.x, registerY + registerHeight - 50f)
                    val endAddressB = Offset(registerX, registerY + registerHeight - 50f)
                    drawLine(
                        color = Color.Black,
                        start = startAddressB,
                        end = endAddressB
                    )
                    drawArrow(startAddressB, endAddressB)
// Draw "5-bit Address" text at the start of the line
                    val addressBText = executionViewModel.rs2.value ?: "No Address"
                    val addressBTextLayout = textMeasurer.measure(
                        text = addressBText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Position the text slightly to the left of the start point
                    drawText(
                        addressBTextLayout,
                        topLeft = Offset(
                            startAddressB.x - addressBTextLayout.size.width - 5f, // 5f padding from the start
                            startAddressB.y - addressBTextLayout.size.height / 2  // Center vertically with the line
                        )
                    )
                    // Calculate the center of the rectangle
                    val centerX = registerX + registerWidth / 2


                    val raX = (canvasWidth / 2) - (raWidth) - 50f
                    val raY = (registerY + registerHeight + padding1)

                    val rbX = (canvasWidth / 2) + 50f
                    val rbY = raY

                    drawLabeledRect(
                        topLeft = Offset(raX, raY),
                        size = Size(raWidth, raHeight),
                        label = executionViewModel.ra.value ?: "RA",
                        textMeasurer = textMeasurer
                    )

                    // RB Rectangle
                    drawLabeledRect(
                        topLeft = Offset(rbX, rbY),
                        size = Size(raWidth, raHeight),
                        label = executionViewModel.rb.value ?: "RB",
                        textMeasurer = textMeasurer
                    )


                    val startForRALine = Offset(registerX + (registerWidth / 2) - 100f, registerY + registerHeight)
                    val endForLineRA = Offset(raX + (raWidth / 2), raY)
                    drawLine(
                        color = Color.Black,
                        start = startForRALine,
                        end = endForLineRA,
                        strokeWidth = 2f
                    )
                    drawArrow(startForRALine, endForLineRA)
                    val startForRB = Offset(registerX + (registerWidth / 2) + 100f, registerY + registerHeight)
                    val endForRB = Offset(rbX + (raWidth / 2), rbY)
                    drawLine(
                        color = Color.Black,
                        start = startForRB,
                        end = endForRB,
                        strokeWidth = 2f
                    )
                    drawArrow(startForRB, endForRB)



                    val centerForMuxB = Offset(rbX + topWidthMuxB / 2, rbY + padding1 + raHeight)
                    val topRightMuxB = Offset(centerForMuxB.x + topWidthMuxB / 2, centerForMuxB.y)

                    drawTrapeziumWithText(
                        center = centerForMuxB,
                        topWidth = topWidthMuxB,
                        bottomWidth = bottomWidthMuxB,
                        height = heightMuxB,
                        text = "MUXB",
                        textMeasurer = textMeasurer
                    )

                    val startImmToMuxB = Offset(centerForMuxB.x + 70f, centerForMuxB.y - 70f)
                    val endImmToMuxB = Offset(centerForMuxB.x + 70f, centerForMuxB.y)
                    drawLine(
                        color = Color.Black,
                        start = startImmToMuxB,
                        end = endImmToMuxB
                    )
                    drawArrow(startImmToMuxB, endImmToMuxB)

                    drawLine(
                        color = Color.Black,
                        start = startImmToMuxB,
                        end = Offset(rbX + raWidth + 10f, centerForMuxB.y - 70f)
                    )

                    val immediateText = executionViewModel.immMuxB.value ?: "0x00000000"
                    val textLayout = textMeasurer.measure(
                        text = immediateText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Adjust position slightly if needed
                    drawText(
                        textLayout,
                        topLeft = Offset(
                            rbX + raWidth + 15f,  // a little offset from the line's end
                            centerForMuxB.y - 70f - textLayout.size.height / 2
                        )
                    )

                    val centerForAlu = Offset(centerX, topRightMuxB.y + heightMuxB+ padding1)


                    drawTrapeziumWithText(
                        centerForAlu,
                        topWidth = topWidthAlu,
                        bottomWidth = bottomWidthAlu,
                        height = heightAlu,
                        "ALU",
                        textMeasurer
                    )

                    val startRaToAlu = Offset(raX + raWidth / 2, raY + raHeight)
                    val endRaToAlu = Offset(raX + raWidth / 2, centerForAlu.y)
                    drawLine(
                        color = Color.Black,
                        start = startRaToAlu,
                        end = endRaToAlu,
                        strokeWidth = 2f
                    )
                    drawArrow(startRaToAlu, endRaToAlu)

                    val startMuxBToAlu = Offset(centerForMuxB.x, centerForMuxB.y + heightMuxB)
                    val endMuxBToAlu = Offset(centerForMuxB.x, centerForAlu.y)
                    drawLine(
                        color = Color.Black,
                        start = startMuxBToAlu,
                        end = endMuxBToAlu,
                        strokeWidth = 2f
                    )
                    drawArrow(startMuxBToAlu, endMuxBToAlu)

                    val startRbToMuxB = Offset(rbX + registerWidth / 2, rbY + raHeight)
                    drawLine(
                        color = Color.Black,
                        start = startRbToMuxB,
                        end = centerForMuxB
                    )
                    drawArrow(startRbToMuxB, centerForMuxB)

                    // rz
                    val rzY = centerForAlu.y + heightAlu + padding2
// Draw the RZ Rectangle
                    drawLabeledRect(
                        topLeft = Offset(registerX, rzY),
                        size = Size(raWidth, raHeight),
                        label = executionViewModel.rz.value ?: "RZ",
                        textMeasurer = textMeasurer
                    )

                    val startAluToRz = Offset(centerForAlu.x, centerForAlu.y + heightAlu)
                    val endAluToRz = Offset(registerX + raWidth / 2, rzY)
                    drawLine(
                        color = Color.Black,
                        start = startAluToRz,
                        end = endAluToRz,
                    )
                    drawArrow(startAluToRz, endAluToRz)

                    val startForMid = Offset(centerForMuxB.x, centerForMuxB.y - (padding1 / 2))
                    val endMidLine = Offset(registerX + topWidthAlu + raWidth / 2, centerForMuxB.y - (padding1 / 2))
                    drawLine(
                        color = Color.Black,
                        start = startForMid,
                        end = endMidLine
                    )

                    val endRbToRm = Offset(endMidLine.x, rzY)
                    drawLine(
                        color = Color.Black,
                        start = endMidLine,
                        end = endRbToRm
                    )
                    drawArrow(endMidLine, endRbToRm)
                    drawLabeledRect(
                        topLeft = Offset(registerX + topWidthAlu, rzY),
                        size = Size(raWidth, raHeight),
                        label = executionViewModel.rm.value ?: "RM",
                        textMeasurer = textMeasurer
                    )

                    val startRmToMdr = Offset(registerX + topWidthAlu + raWidth, rzY + raHeight / 2)
                    val endRmToMdr = Offset(registerX + topWidthAlu + raWidth + 20f, rzY + raHeight / 2)
                    drawLine(
                        color = Color.Black,
                        start = startRmToMdr,
                        end = endRmToMdr
                    )
                    drawArrow(startRmToMdr, endRmToMdr)


// Measure the "MDR" text
                    val mdrLabelLayout = textMeasurer.measure(
                        text = "MDR (${executionViewModel.mdr.value})",
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw "MDR" next to the arrowhead
                    drawText(
                        mdrLabelLayout,
                        topLeft = Offset(
                            endRmToMdr.x + 10f,  // Slightly right of the arrow tip
                            endRmToMdr.y - mdrLabelLayout.size.height / 2  // Vertically centered with the line
                        )
                    )


                    val centerForMuxY = Offset(centerForAlu.x, rzY + raHeight +  padding3)
                    //MuxY
                    drawTrapeziumWithText(
                        center = centerForMuxY,
                        topWidth = topWidthMuxB,
                        bottomWidth = bottomWidthMuxB,
                        height = heightMuxB,
                        text = "MUXY",
                        textMeasurer = textMeasurer
                    )


                    val startMdrToMuxY = Offset(centerForMuxY.x, centerForMuxY.y - (2 * padding3) / 3)
                    drawLine(
                        color = Color.Black,
                        start = startMdrToMuxY,
                        end = centerForMuxY
                    )
                    drawArrow(startMdrToMuxY, centerForMuxY)

                    drawLine(
                        color = Color.Black,
                        start = startMdrToMuxY,
                        end = Offset(centerForMuxY.x + 30f, startMdrToMuxY.y)
                    )

                    // Measure the "MDR" text
                    val mdrTextLayout = textMeasurer.measure(
                        text = "MDR (${executionViewModel.mdr.value})",
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw the text at the end of the line with slight right offset
                    drawText(
                        mdrTextLayout,
                        topLeft = Offset(
                            centerForMuxY.x + 35f, // Slightly beyond the line end
                            startMdrToMuxY.y - mdrTextLayout.size.height / 2 // Centered vertically
                        )
                    )

                    val startReturnToMuxY = Offset(centerForMuxY.x + 90f, centerForMuxY.y - 30f)
                    val endReturnToMuxY = Offset(centerForMuxY.x + 90f, centerForMuxY.y)
                    drawLine(
                        color = Color.Black,
                        start = startReturnToMuxY,
                        end = endReturnToMuxY
                    )

                    drawArrow(startReturnToMuxY, endReturnToMuxY)

                    drawLine(
                        color = Color.Black,
                        start = startReturnToMuxY,
                        end = Offset(startReturnToMuxY.x + 30f, startReturnToMuxY.y)
                    )

                    // Measure the "Return Address" text
                    val returnAddressLayout = textMeasurer.measure(
                        text = "Return Address (${executionViewModel.pcTemp.value})",
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw the text at the end of the line with slight offset
                    drawText(
                        returnAddressLayout,
                        topLeft = Offset(
                            startReturnToMuxY.x + 35f,  // 5px offset after the line end
                            startReturnToMuxY.y - returnAddressLayout.size.height / 2
                        )
                    )

                    val startRzToMuxY = Offset(centerForMuxY.x - 90f, rzY + raHeight)
                    val endRzToMuxY = Offset(centerForMuxY.x - 90f, centerForMuxY.y)

                    drawLine(
                        color = Color.Black,
                        start = startRzToMuxY,
                        end = endRzToMuxY
                    )
                    drawArrow(startRzToMuxY, endRzToMuxY)

                    val endRzToMar = Offset(registerX + raWidth, startRzToMuxY.y + 20f)
                    val startRzToMar = Offset(startRzToMuxY.x, startRzToMuxY.y + 20f)
                    drawLine(
                        color = Color.Black,
                        start = startRzToMar,
                        end = endRzToMar
                    )

                    drawArrow(startRzToMar, endRzToMar)
                    // Measure the "MAR" text
                    val marTextLayout = textMeasurer.measure(
                        text = "MAR (${executionViewModel.mar.value})",
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw the text right after the arrow, horizontally aligned
                    drawText(
                        marTextLayout,
                        topLeft = Offset(
                            endRzToMar.x + 10f,  // Slightly right to avoid overlapping the arrow
                            endRzToMar.y - marTextLayout.size.height / 2
                        )
                    )
//                ry
                    val ryY = centerForMuxY.y + heightMuxB + padding2

                    val startMuxYToRY = Offset(centerForMuxY.x, centerForMuxY.y + heightMuxB)
                    val endMuxYToRy = Offset(registerX + raWidth / 2, ryY)
                    drawLine(
                        color = Color.Black,
                        start = startMuxYToRY,
                        end = endMuxYToRy
                    )
                    drawArrow(startMuxYToRY, endMuxYToRy)
                    drawLabeledRect(
                        topLeft = Offset(registerX, ryY),
                        size = Size(raWidth, raHeight),
                        label = executionViewModel.ry.value ?: "RY",
                        textMeasurer = textMeasurer
                    )

                    drawLine(
                        color = Color.Black,
                        start = Offset(registerX, ryY + raHeight / 2),
                        end = Offset(registerX - 300f, ryY + raHeight / 2)
                    )
                    drawLine(
                        color = Color.Black,
                        start = Offset(registerX - 300f, ryY + raHeight / 2),
                        end = Offset(registerX - 300f, registerY - 30f)
                    )
                    drawLine(
                        color = Color.Black,
                        start = Offset(registerX - 300f, registerY - 30f),
                        end = Offset(registerX + registerWidth / 2, registerY - 30f)
                    )
                    val startRyToRegister = Offset(registerX + registerWidth / 2, registerY - 30f)
                    val endRyToRegister = Offset(registerX + registerWidth / 2, registerY)
                    drawLine(
                        color = Color.Black,
                        start = startRyToRegister,
                        end = endRyToRegister
                    )
                    drawArrow(
                        startRyToRegister,
                        endRyToRegister
                    )

                    val centerForMuxMa = Offset(canvasWidth / 2 - topWidthAlu / 2 - 30f, ryY + raWidth + padding3)
                    drawTrapeziumWithText(
                        centerForMuxMa,
                        topWidthAlu,
                        bottomWidthAlu,
                        heightAlu,
                        "MUXMA",
                        textMeasurer
                    )
                    val startRzToMux = Offset(centerForMuxMa.x - 100f, centerForMuxMa.y - 100f)
                    val endRzToMux = Offset(centerForMuxMa.x - 100f, centerForMuxMa.y)
                    drawLine(
                        color = Color.Black,
                        start = startRzToMux,
                        end = endRzToMux
                    )
                    drawArrow(startRzToMux, endRzToMux)

                    val rzText = "RZ (${executionViewModel.rz.value})"
                    val rzTextLayout = textMeasurer.measure(
                        text = rzText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Place "RZ" horizontally centered with the line and slightly left of start point
                    drawText(
                        rzTextLayout,
                        topLeft = Offset(
                            startRzToMux.x - rzTextLayout.size.width / 2 - 5f, // 5f padding from line start
                            startRzToMux.y - rzTextLayout.size.height  // Center vertically with the line
                        )
                    )

                    val startIAGToMux = Offset(centerForMuxMa.x + 100f, centerForMuxMa.y - 100f)
                    val endIAGToMux = Offset(centerForMuxMa.x + 100f, centerForMuxMa.y)
                    drawLine(
                        color = Color.Black,
                        start = startIAGToMux,
                        end = endIAGToMux
                    )
                    drawArrow(startIAGToMux, endIAGToMux)

                    val iagText = "IAG (${executionViewModel.pcMuxPc.value})"
                    val iagTextLayout = textMeasurer.measure(
                        text = iagText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw "IAG" to the left of the starting point, vertically centered with the line
                    drawText(
                        iagTextLayout,
                        topLeft = Offset(
                            startIAGToMux.x - 10f,  // 5f padding left of line
                            startIAGToMux.y - iagTextLayout.size.height   // Center vertically on the line
                        )
                    )



                    val centerForIRMux = Offset(canvasWidth / 2 + topWidthAlu / 2 + 30f, centerForMuxMa.y)
                    drawTrapeziumWithText(
                        centerForIRMux,
                        topWidthAlu,
                        bottomWidthAlu,
                        heightAlu,
                        "MUX",
                        textMeasurer
                    )

                    val startMuxToY = Offset(centerForIRMux.x - 100f, centerForIRMux.y)
                    val endMuxToY = Offset(centerForIRMux.x - 100f, centerForIRMux.y - 100f)
                    drawLine(
                        color = Color.Black,
                        start = startMuxToY,
                        end = endMuxToY
                    )
                    drawArrow(startMuxToY, endMuxToY)

                    val muxYText = "muxY"
                    val muxYTextLayout = textMeasurer.measure(
                        text = muxYText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw "muxY" slightly above the end point, horizontally centered
                    drawText(
                        muxYTextLayout,
                        topLeft = Offset(
                            endMuxToY.x - muxYTextLayout.size.width / 2,
                            endMuxToY.y - muxYTextLayout.size.height    // 5f padding above
                        )
                    )

                    val endMuxToIr = Offset(centerForIRMux.x + 100f, centerForIRMux.y - 100f)
                    val startMuxToIr = Offset(centerForIRMux.x + 100f, centerForIRMux.y)
                    drawLine(
                        color = Color.Black,
                        start = startMuxToIr,
                        end = endMuxToIr
                    )
                    drawArrow(startMuxToIr, endMuxToIr)

                    val irText = "IR"
                    val irTextLayout = textMeasurer.measure(
                        text = irText,
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )

// Draw "IR" centered horizontally at the end of the arrow
                    drawText(
                        irTextLayout,
                        topLeft = Offset(
                            endMuxToIr.x - irTextLayout.size.width / 2,
                            endMuxToIr.y - irTextLayout.size.height - 5f // Slightly above the arrowhead
                        )
                    )


                    val pmiWidth = 600f
                    val pmiHeight = heightAlu

                    val pmiX = canvasWidth / 2 - pmiWidth / 2
                    val pmiY = centerForIRMux.y + heightAlu + padding1

// Draw PMI outer rectangle
                    drawRect(
                        color = Color.LightGray,
                        topLeft = Offset(pmiX, pmiY),
                        size = Size(pmiWidth, pmiHeight)
                    )

                    val startMuxToPmi = Offset(pmiX + 50f, centerForMuxMa.y + heightAlu)
                    val endMuxToPmi = Offset(pmiX + 50, pmiY)
                    drawLine(
                        color = Color.Black,
                        start = startMuxToPmi,
                        end = endMuxToPmi
                    )
                    drawArrow(startMuxToPmi, endMuxToPmi)

                    val startPmiToMux = Offset(pmiX + pmiWidth - 50f, pmiY)
                    val endPmiToMux = Offset(pmiX + pmiWidth - 50f, centerForMuxMa.y + heightAlu)
                    drawLine(
                        color = Color.Black,
                        start = startPmiToMux,
                        end = endPmiToMux
                    )
                    drawArrow(startPmiToMux, endPmiToMux)

                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(pmiX, pmiY),
                        size = Size(pmiWidth, pmiHeight),
                        style = Stroke(width = 2f)
                    )


// Calculate MAR and MDR sizes (half width)
                    val subWidth = pmiWidth / 3
                    val subHeight = pmiHeight / 2

// Draw MAR box on the left
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(pmiX, pmiY),
                        size = Size(subWidth, subHeight),
                        style = Stroke(width = 2f)
                    )

// Draw MDR box on the right
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(pmiX + pmiWidth - subWidth, pmiY),
                        size = Size(subWidth, subHeight),
                        style = Stroke(width = 2f)
                    )

// Write "MAR" in the MAR box
                    val marLayout = textMeasurer.measure(
                        text = "MAR (${executionViewModel.mar.value})",
                        style = TextStyle(color = Color.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
                    )
                    drawText(
                        marLayout,
                        topLeft = Offset(
                            pmiX + subWidth / 2 - marLayout.size.width / 2,
                            pmiY + subHeight / 2 - marLayout.size.height / 2
                        )
                    )

// Write "MDR" in the MDR box
                    val mdrLayout = textMeasurer.measure(
                        text = "MDR (${executionViewModel.mdr.value})",
                        style = TextStyle(color = Color.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
                    )
                    drawText(
                        mdrLayout,
                        topLeft = Offset(
                            pmiX + pmiWidth - subWidth / 2 - mdrLayout.size.width / 2,
                            pmiY + subHeight / 2 - mdrLayout.size.height / 2
                        )
                    )

// Write "PMI" at the bottom-center of the PMI block
                    val pmiLayout = textMeasurer.measure(
                        text = "PMI",
                        style = TextStyle(color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    )
                    drawText(
                        pmiLayout,
                        topLeft = Offset(
                            pmiX + pmiWidth / 2 - pmiLayout.size.width / 2,
                            pmiY + pmiHeight - pmiLayout.size.height - 5f  // Slight padding from bottom
                        )
                    )


                    drawLabeledRect(
                        topLeft = Offset(pmiX, pmiY + heightAlu + padding1),
                        size = Size(pmiWidth, pmiHeight),
                        label = "Memory",
                        textMeasurer
                    )

                    val startPmiToMem = Offset(pmiX + pmiWidth / 2 - 100f, pmiY + pmiHeight)
                    val endPmiToMem = Offset(pmiX + pmiWidth / 2 - 100f, pmiY + heightAlu + padding1)
                    drawLine(
                        color = Color.Black,
                        start = startPmiToMem,
                        end = endPmiToMem
                    )
                    drawArrow(startPmiToMem, endPmiToMem)

                    val startMemToPmi = Offset(endPmiToMem.x + 200f, endPmiToMem.y)
                    val endMemToPmi = Offset(startPmiToMem.x + 200f, startPmiToMem.y)
                    drawLine(
                        color = Color.Black,
                        start = startMemToPmi,
                        end = endMemToPmi
                    )
                    drawArrow(startMemToPmi, endMemToPmi)
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(verticalScrollState)
            )
            HorizontalScrollbar(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                adapter = rememberScrollbarAdapter(horizontalScrollState)
            )

        }

    }
}

fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color = Color.Black, arrowHeadSize: Float = 20f) {
    // Draw main line
    drawLine(color = color, start = start, end = end, strokeWidth = 2f)

    // Calculate angle
    val angle = atan2(end.y - start.y, end.x - start.x)

    // Arrowhead sides
    val angleOffset = PI / 6 // 30 degree spread
    val arrowPoint1 = Offset(
        end.x - (arrowHeadSize * cos(angle - angleOffset)).toFloat(),
        end.y - (arrowHeadSize * sin(angle - angleOffset)).toFloat()
    )
    val arrowPoint2 = Offset(
        end.x - (arrowHeadSize * cos(angle + angleOffset)).toFloat(),
        end.y - (arrowHeadSize * sin(angle + angleOffset)).toFloat()
    )

    // Draw arrowhead
    drawLine(color = color, start = end, end = arrowPoint1, strokeWidth = 2f)
    drawLine(color = color, start = end, end = arrowPoint2, strokeWidth = 2f)
}

fun DrawScope.drawTrapeziumWithText(
    center: Offset,
    topWidth: Float,
    bottomWidth: Float,
    height: Float,
    text: String,
    textMeasurer: TextMeasurer,
    fillColor: Color = Color.LightGray,
    borderColor: Color = Color.Black
) {
    // Calculate corner points based on center, widths, and height
    val topLeft = Offset(center.x - topWidth / 2, center.y)
    val topRight = Offset(center.x + topWidth / 2, center.y)
    val bottomLeft = Offset(center.x - bottomWidth / 2, center.y + height)
    val bottomRight = Offset(center.x + bottomWidth / 2, center.y + height)

    // Create the trapezium path
    val path = Path().apply {
        moveTo(topLeft.x, topLeft.y)
        lineTo(topRight.x, topRight.y)
        lineTo(bottomRight.x, bottomRight.y)
        lineTo(bottomLeft.x, bottomLeft.y)
        close()
    }

    // Fill color
    drawPath(path = path, color = fillColor)

    // Border
    drawPath(path = path, color = borderColor, style = Stroke(width = 3f))

    // Compute center for text
    val centerX = center.x
    val centerY = center.y + height / 2

    // Measure text
    val textLayoutResult = textMeasurer.measure(
        text = text,
        style = TextStyle(
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )

    // Draw text centered
    drawText(
        textLayoutResult,
        topLeft = Offset(
            centerX - textLayoutResult.size.width / 2,
            centerY - textLayoutResult.size.height / 2
        )
    )
}

fun DrawScope.drawLabeledRect(
    topLeft: Offset,
    size: Size,
    label: String,
    textMeasurer: TextMeasurer,
    fillColor: Color = Color.LightGray,
    borderColor: Color = Color.Black
) {
    // Draw rectangle
    drawRect(
        color = fillColor,
        topLeft = topLeft,
        size = size,
        style = Fill
    )

    // Optional border
    drawRect(
        color = borderColor,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = 2f)
    )

    // Center coordinates for text
    val centerX = topLeft.x + size.width / 2
    val centerY = topLeft.y + size.height / 2

    // Measure text
    val textLayout = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )

    // Draw text centered
    drawText(
        textLayout,
        topLeft = Offset(
            centerX - textLayout.size.width / 2,
            centerY - textLayout.size.height / 2
        )
    )
}




