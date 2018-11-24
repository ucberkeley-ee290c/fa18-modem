package modem

import chisel3._
import chisel3.util._
import dsptools.numbers._
//import freechips.rocketchip.diplomacy.LazyModule
//import freechips.rocketchip.subsystem.BaseSubsystem

// Written by Kunmo Kim : kunmok@berkeley.edu
// Description: Viterbi decoder + de-puncturing + header extractor
class ViterbiDecoder[T <: Data: Real](params: CodingParams[T]) extends Module {
  require(params.m > 1)
  require(params.k > 0)
  require(params.n > 0)

  val io = IO(new Bundle {
    val in        = Input(Vec(params.O, SInt(2.W)))
    val inReady   = Input(UInt(1.W))
    val out     = Decoupled(Vec(params.D, UInt(params.k.W)))
    val out_dp  = Output(Vec(params.n, SInt(2.W)))
    val out_pm  = Output(Vec(params.nStates, UInt(params.pmBits.W)))  // storing Path Metric
    val out_sp  = Output(Vec(params.nStates, UInt(params.m.W)))       // storing Survival Path
  })
  /*
  val allDataReceived  = RegInit(0.U(6.W))
  val headTrackReg     = RegInit(0.U(1.W){
  when(pktStart === 1.U && allDataReceived === 1.U){
    headTrackReg := 1.U
  }.elsewhen(pktEnd === 1.U && allDataReceived === 1.U){
    headTrackReg := 0.U
  }
  val isHead = Mux((pktStart || headTrackReg) === 1.U, 1.U, 0.U)
  */
  val HeaderExtModule             = Module(new HeaderExtractor[T](params))
  val DePuncturingModule          = Module(new DePuncturing[T](params))
  val pathMetricModule            = Module(new PathMetric[T](params))
  val tracebackModule             = Module(new Traceback[T](params))

  // Header-Extractor connection
  HeaderExtModule.io.in           <> io.in


  DePuncturingModule.io.in_hard   <> io.in
  DePuncturingModule.io.headInfo  := HeaderExtModule.io.headInfo
  DePuncturingModule.io.inReady   := io.inReady
  DePuncturingModule.io.stateIn   := 0.U
  io.out_dp                       <> DePuncturingModule.io.outData

  pathMetricModule.io.in          <> DePuncturingModule.io.outData
  pathMetricModule.io.inReady     := io.inReady
  io.out_pm <> pathMetricModule.io.outPM
  io.out_sp <> pathMetricModule.io.outSP

  tracebackModule.io.inPM         := pathMetricModule.io.outPM
  tracebackModule.io.inSP         := pathMetricModule.io.outSP
  tracebackModule.io.inReady      := io.inReady
  io.out    <> tracebackModule.io.out
}