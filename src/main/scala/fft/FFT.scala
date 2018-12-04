package modem

import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util._
import dsptools.numbers._
import breeze.numerics.{cos, sin}
import breeze.signal.fourierTr
import breeze.linalg.DenseVector
import breeze.math.Complex
import scala.math._

/**
 * Base class for FFT parameters
 *
 * These are type generic
 */
trait FFTParams[T <: Data] extends PacketBundleParams[T] {
  val numPoints    : Int           // number of points in FFT
  val protoTwiddle : DspComplex[T] // twiddle data type
  val fftType      : String        // type of FFT to use
  val decimType    : String        // if SDF FFT is being used, whether to use DIT, DIF, or whatever is more "optimal"
  val pipeline     : Boolean       // if direct FFT is being used, whether to pipeline the FFT stages
  lazy val width = numPoints       // overrides the `width` parameter of PacketBundleParams

  // Allowed values for some parameters
  final val allowedFftTypes   = Seq("direct", "sdf")
  final val allowedDecimTypes = Seq("dit", "dif", "opt")

  // Common require functions used in FFT blocks
  def checkNumPointsPow2() {
    require(isPow2(numPoints), "number of points must be a power of 2")
  }
  def checkNumPointsPow() {
    require(FFTUtil.is_power(numPoints), "number of points must be a power of some number")
  }
  def checkFftType() {
    require(allowedFftTypes.contains(fftType), s"""FFT type must be one of the following: ${allowedFftTypes.mkString(", ")}""")
  }
  def checkDecimType() {
    require(allowedDecimTypes.contains(decimType), s"""Decimation type must be one of the following: ${allowedDecimTypes.mkString(", ")}""")
  }
  def getPowerInfo(): (Int, Int) = {
    (FFTUtil.factorize(numPoints)._1.head, FFTUtil.factorize(numPoints)._2.head)
  }
}
object FFTParams {
  // Override number of points
  def apply[T <: Data](old_params: FFTParams[T], newNumPoints: Int): FFTParams[T] = new FFTParams[T] {
    val protoIQ      = old_params.protoIQ
    val protoTwiddle = old_params.protoTwiddle
    val numPoints    = newNumPoints
    val pipeline     = old_params.pipeline
    val fftType      = old_params.fftType
    val decimType    = old_params.decimType
  }
  // Override decimation type
  def apply[T <: Data](old_params: FFTParams[T], newDecimType: String): FFTParams[T] = new FFTParams[T] {
    val protoIQ      = old_params.protoIQ
    val protoTwiddle = old_params.protoTwiddle
    val numPoints    = old_params.numPoints
    val pipeline     = old_params.pipeline
    val fftType      = old_params.fftType
    val decimType    = newDecimType
  }
}

/**
 * FFT parameters case class for fixed-point FFTs
 */
case class FixedFFTParams(
  dataWidth   : Int, // width of input and output
  binPoint    : Int, // binary point of input and output
  twiddleWidth: Int, // width of twiddle constants
  numPoints   : Int,
  pipeline    : Boolean = true,
  fftType     : String = "sdf",
  decimType   : String = "opt"
) extends FFTParams[FixedPoint] {
  val protoIQ      = DspComplex(FixedPoint(dataWidth.W, binPoint.BP))
  val protoTwiddle = DspComplex(FixedPoint(twiddleWidth.W, (twiddleWidth-2).BP)) // to allow for 1, -1, j, and -j to be expressed.
}

/**
 * Bundle type as IO for various blocks
 *
 * Many blocks use serial/parallel input and serial/parallel output streams
 */
// serial input, serial output
class SISOIO[T <: Data : Ring](params: PacketBundleParams[T]) extends Bundle {
  val in = Flipped(Decoupled(PacketBundle(1, params.protoIQ)))
  val out = Decoupled(PacketBundle(1, params.protoIQ))

  override def cloneType: this.type = SISOIO(params).asInstanceOf[this.type]
}
object SISOIO {
  def apply[T <: Data : Ring](params: PacketBundleParams[T]): SISOIO[T] = new SISOIO(params)
}

// serial input, deserial output
class SIDOIO[T <: Data : Ring](params: PacketBundleParams[T]) extends Bundle {
  val in = Flipped(Decoupled(PacketBundle(1, params.protoIQ)))
  val out = Decoupled(PacketBundle(params))

  override def cloneType: this.type = SIDOIO(params).asInstanceOf[this.type]
}
object SIDOIO {
  def apply[T <: Data : Ring](params: PacketBundleParams[T]): SIDOIO[T] = new SIDOIO(params)
}

// deserial input, serial output
class DISOIO[T <: Data : Ring](params: PacketBundleParams[T]) extends Bundle {
  val in = Flipped(Decoupled(PacketBundle(params)))
  val out = Decoupled(PacketBundle(1, params.protoIQ))

  override def cloneType: this.type = DISOIO(params).asInstanceOf[this.type]
}
object DISOIO {
  def apply[T <: Data : Ring](params: PacketBundleParams[T]): DISOIO[T] = new DISOIO(params)
}

// deserial input, deserial output
class DIDOIO[T <: Data : Ring](params: PacketBundleParams[T]) extends Bundle {
  val in = Flipped(Decoupled(PacketBundle(params)))
  val out = Decoupled(PacketBundle(params))

  override def cloneType: this.type = DIDOIO(params).asInstanceOf[this.type]
}
object DIDOIO {
  def apply[T <: Data : Ring](params: PacketBundleParams[T]): DIDOIO[T] = new DIDOIO(params)
}


/**
 * Top level FFT
 *
 * Instantiates the correct type of FFT based on parameter value
 */
class FFT[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  val io = IO(SIDOIO(params))
  params.checkFftType()
  params.checkDecimType()

  params.fftType match {
    case "direct" => {
      // instantiate PacketDeserializer to go from serial-input FFT to parallel-input DirectFFT
      val deser = Module(new PacketDeserializer(PacketSerDesParams(params.protoIQ.cloneType, params.numPoints)))
      val fft   = Module(new DirectFFT(params))
      deser.io.in <> io.in
      fft.io.in   <> deser.io.out
      io.out      <> fft.io.out
    }
    case "sdf" => {
      val fft = Module(new SDFFFTDeserOut(params))
      fft.io.in  <> io.in
      fft.io.out <> io.out
    }
  }
}

/**
 * Top level IFFT
 *
 * Instantiates the correct type of FFT based on parameter value
 */
class IFFT[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  val io = IO(DISOIO(params))
  params.checkFftType()
  params.checkDecimType()

  val fft_in  = Wire(io.in.cloneType)
  val fft_out = Wire(io.out.cloneType)

  // Bulk connect, but iq will be overridden in a following block of code
  fft_in  <> io.in
  fft_out <> io.out

  io.in.bits.iq.zip(fft_in.bits.iq).foreach {
    case (io_in, fft_inp) => {
      // Swap real and imaginary components
      fft_inp.real := io_in.imag
      fft_inp.imag := io_in.real
    }
  }

  val scalar = ConvertableTo[T].fromDouble(1.0 / params.numPoints.toDouble) // normalization factor
  // Swap real and imaginary components and normalize
  io.out.bits.iq(0).real := fft_out.bits.iq(0).imag * scalar
  io.out.bits.iq(0).imag := fft_out.bits.iq(0).real * scalar

  params.fftType match {
    case "direct" => {
      // instantiate PacketSerializer to go from parallel-output DirectFFT to serial-output FFT
      val ser = Module(new PacketSerializer(PacketSerDesParams(params.protoIQ.cloneType, params.numPoints)))
      val fft = Module(new DirectFFT(params))
      fft_in    <> fft.io.in
      ser.io.in <> fft.io.out
      fft_out   <> ser.io.out
    }
    case "sdf" => {
      val fft = Module(new SDFFFTDeserIn(params))
      fft.io.in <> fft_in
      fft_out   <> fft.io.out
    }
  }
}

/**************************************************
 * Direct FFT (top level and sub-blocks)
 **************************************************/

/**
 * Top level Direct FFT block
 */
class DirectFFT[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  val io = IO(DIDOIO(params))
  val fft_stage = if (FFTUtil.is_power(params.numPoints)) {
    Module(new CooleyTukeyStage(params))
  } else {
    Module(new PFAStage(params))
  }
  fft_stage.io.in := io.in.bits.iq
  io.in.ready     := io.out.ready
  io.out.bits.iq  := fft_stage.io.out

  val delay = FFTUtil.factorize(params.numPoints)._2.reduce(_ + _) - 1
  if (params.pipeline && delay > 0) {
    io.out.bits.pktStart := ShiftRegister(io.in.bits.pktStart, delay)
    io.out.bits.pktEnd   := ShiftRegister(io.in.bits.pktEnd  , delay)
    io.out.valid         := ShiftRegister(io.in.valid        , delay, false.B, true.B)
  } else {
    io.out.bits.pktStart := io.in.bits.pktStart
    io.out.bits.pktEnd   := io.in.bits.pktEnd
    io.out.valid         := io.in.valid
  }
}

/**
 * Bundle type as IO for direct FFT stage
 */
class DirectStageIO[T <: Data : Ring](params: FFTParams[T]) extends Bundle {
  val in  = Input(PacketBundle(params).iq)
  val out = Output(PacketBundle(params).iq)

  override def cloneType: this.type = DirectStageIO(params).asInstanceOf[this.type]
}
object DirectStageIO {
  def apply[T <: Data : Ring](params: FFTParams[T]): DirectStageIO[T] = new DirectStageIO(params)
}

/**
 * "Stage" for Direct FFT
 *
 * Recursively instantiates smaller stages/DFTs based on the Cooley-Tukey algorithm decimation-in-time
 */
class CooleyTukeyStage[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  params.checkNumPointsPow()
  val io = IO(DirectStageIO(params))

  val (base, numStages) = params.getPowerInfo()
  val numPointsDivBase = params.numPoints / base
  // generate twiddle constants
  val numTwiddles = (base - 1) * (numPointsDivBase - 1) + 1
  val twiddlesSeq = (0 until numTwiddles).map(n => {
    val twiddle_wire = Wire(params.protoTwiddle.cloneType)
    twiddle_wire.real := Real[T].fromDouble( cos(2 * Pi / params.numPoints * n))
    twiddle_wire.imag := Real[T].fromDouble(-sin(2 * Pi / params.numPoints * n))
    twiddle_wire
  })

  if (params.numPoints == base) {
    io.out.zip(Butterfly[T](io.in.seq, params.protoTwiddle)).foreach {
      case (out_wire, out_val) => out_wire := out_val 
    }
  } else {
    // Create sub-FFTs
    val new_params = FFTParams(params, numPointsDivBase)
    val sub_stg_outputs = (0 until base).map {
      case i => {
        val stage = Module(new CooleyTukeyStage(new_params))
        stage.io.in       := io.in.zipWithIndex.filter(_._2 % base == i).map(_._1)

        if (params.pipeline) {
          // Register the outputs of sub-fft stages
          RegNext(stage.io.out)
        } else {
          stage.io.out
        }
      }
    }

    (0 until numPointsDivBase).map(n => {
      val butterfly_inputs = Seq(sub_stg_outputs.head(n)) ++ sub_stg_outputs.zipWithIndex.tail.map {
        case (stg_output, idx) => stg_output(n) * twiddlesSeq(idx * n)
      }

      val butterfly_outputs = Butterfly[T](butterfly_inputs, params.protoTwiddle)
      butterfly_outputs.zipWithIndex.foreach {
        case (out_val, idx) => io.out(n + numPointsDivBase * idx) := out_val
      }
    })
  }
}

class PFAStage[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  require(!FFTUtil.is_power(params.numPoints), "number of points must not be a power of some number")
  val io = IO(DirectStageIO(params))

  val factorized = FFTUtil.factorize(params.numPoints)

  val N1 = scala.math.pow(factorized._1.head, factorized._2.head).toInt
  val N2 = params.numPoints / N1

  val invN1 = FFTUtil.mult_inv(N1 % N2, N2)
  val invN2 = FFTUtil.mult_inv(N2 % N1, N1)

  val first_stage_params = FFTParams(params, N1)
  val rest_stage_params  = FFTParams(params, N2)

  val first_stage_outputs = (0 until N2).map(n2 => {
    val stage = Module(new CooleyTukeyStage(first_stage_params))
    stage.io.in.zipWithIndex.map {
      case (stage_in, n1) => {
        // Good's mapping
        val inp_idx = (N1 * n2 + N2 * n1) % params.numPoints
        stage_in := io.in(inp_idx)
      }
    }
    stage.io.out
  })

  val rest_stage_outputs = (0 until N1).map(k1 => {
    val stage = if (FFTUtil.is_power(N2)) {
      Module(new CooleyTukeyStage(rest_stage_params))
    } else {
      Module(new PFAStage(rest_stage_params))
    }
    stage.io.in.zipWithIndex.map {
      case (rest_stage_in, idx) => {
        if (params.pipeline) {
          // Register the outputs of sub-FFT stages
          rest_stage_in := RegNext(first_stage_outputs(idx)(k1))
        } else {
          rest_stage_in := first_stage_outputs(idx)(k1)
        }
      }
    }

    stage.io.out.zipWithIndex.map {
      case (stage_out, k2) => {
        // CRT mapping
        val out_idx = (N1 * invN1 * k2 + N2 * invN2 * k1) % params.numPoints
        io.out(out_idx) := stage_out
      }
    }
    stage.io.out
  })
}

/**************************************************
 * Single-Path Delay Feedback FFT (top level and sub-blocks)
 **************************************************/

/**
 * Top level SDF FFT block
 *
 * For serial-input serial-output, arbitrarily choose DIT for "optimal" decimation type setting
 */
class SDFFFT[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  params.checkNumPointsPow2()
  val io = IO(SISOIO(params))
  val serdes_params = PacketSerDesParams(params.protoIQ.cloneType, params.numPoints)

  if (params.decimType == "dif") {
    val ser               = Module(new PacketSerializer(serdes_params))
    val sdf_fft_deser_out = Module(new SDFFFTDeserOut(params))
    io.in     <> sdf_fft_deser_out.io.in
    ser.io.in <> sdf_fft_deser_out.io.out
    io.out    <> ser.io.out
  } else {
    val des              = Module(new PacketDeserializer(serdes_params))
    val sdf_fft_deser_in = Module(new SDFFFTDeserIn(params))
    io.in      <> des.io.in
    des.io.out <> sdf_fft_deser_in.io.in
    io.out     <> sdf_fft_deser_in.io.out
  }
}

/**
 * Top level SDF FFT block for parallel/deserialized input
 *
 * For parallel-input serial-output, "optimal" setting is DIT (unscrambler is at the input)
 */
class SDFFFTDeserIn[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T], val dit: Boolean = true) extends Module {
  params.checkNumPointsPow2()
  val io = IO(DISOIO(params))

  val updated_params = if (params.decimType == "opt") FFTParams(params, "dit") else params
  val serdes_params  = PacketSerDesParams(params.protoIQ.cloneType, params.numPoints)
  val inp_ser     = Module(new PacketSerializer(serdes_params))
  val unscrambler = Module(new FFTUnscrambler(updated_params))
  val sdf_chain   = Module(new SDFChain(updated_params))

  val out_if = if (updated_params.decimType == "dit") {
    // Data flow: Unscrambler -> PacketSerializer -> SDF Chain
    unscrambler.io.in <> io.in
    inp_ser.io.in     <> unscrambler.io.out
    sdf_chain.io.out
  } else {
    // Data flow: PacketSerializer -> SDF Chain -> PacketDeserializer -> Unscrambler -> PacketSerializer
    val ser = Module(new PacketSerializer(serdes_params))
    val des = Module(new PacketDeserializer(serdes_params))
    inp_ser.io.in <> io.in
    des.io.in     <> sdf_chain.io.out
    des.io.out    <> unscrambler.io.in
    ser.io.in     <> unscrambler.io.out
    ser.io.out
  }
  inp_ser.io.out <> sdf_chain.io.in
  io.out         <> out_if
}

/**
 * Top level SDF FFT block for parallel/deserialized output
 *
 * For serial-input parallel-output, "optimal" setting is DIF (unscrambler is at the output)
 */
class SDFFFTDeserOut[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  params.checkNumPointsPow2()
  val io = IO(SIDOIO(params))

  val updated_params = if (params.decimType == "opt") FFTParams(params, "dif") else params
  val serdes_params  = PacketSerDesParams(params.protoIQ.cloneType, params.numPoints)
  val out_des     = Module(new PacketDeserializer(serdes_params))
  val unscrambler = Module(new FFTUnscrambler(updated_params))
  val sdf_chain   = Module(new SDFChain(updated_params))

  val inp_if = if (updated_params.decimType == "dif") {
    // Data flow: SDF Chain -> PacketDeserializer -> Unscrambler
    unscrambler.io.in  <> out_des.io.out
    unscrambler.io.out <> io.out
    sdf_chain.io.in
  } else {
    // Data flow: PacketDeserializer -> Unscrambler -> PacketSerializer -> SDF Chain -> PacketDeserializer
    val ser = Module(new PacketSerializer(serdes_params))
    val des = Module(new PacketDeserializer(serdes_params))
    out_des.io.out <> io.out
    ser.io.out     <> sdf_chain.io.in
    ser.io.in      <> unscrambler.io.out
    des.io.out     <> unscrambler.io.in
    des.io.in
  }
  out_des.io.in <> sdf_chain.io.out
  inp_if        <> io.in
}

/**
 * SDF FFT Unscrambler
 *
 * Reorders parallel data by bit reversion
 */
class FFTUnscrambler[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  val io = IO(DIDOIO(params))

  // Bulk connect, but iq field will be re-connected in the following block of code
  io.out <> io.in

  (0 until params.numPoints).foreach(i => {
    val index = i.U(log2Up(params.numPoints).W)
    val reversed_index = Reverse(index)
    io.out.bits.iq(reversed_index) := io.in.bits.iq(index)
  })
}

/**
 * SDF FFT Chain
 *
 * Instantiates and connects SDF FFT stages in series and provides necessary control signals for each stage
 */
class SDFChain[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T]) extends Module {
  params.checkNumPointsPow2()
  // At this point, "opt" decimType should already have been resolved to "dit" or "dif"
  require(Seq("dit", "dif").contains(params.decimType), s"""Decimation type must either be dit or dif""")
  val io = IO(SISOIO(params))

  // Calculation of constants
  val numPointsDiv2     = params.numPoints / 2                                                                // FFT size / 2
  val numStages         = log2Up(params.numPoints)                                                            // required number of SDF stages for given FFT size
  val delayLog2s        = if (params.decimType == "dit") (0 until numStages) else (0 until numStages).reverse // log2(delay) of each nth stage
  val delays            = delayLog2s.map(d => scala.math.pow(2, d).toInt)                                     // actual delay of each nth stage
  val cumulative_delays = delays.scanLeft(0)(_ + _)                                                           // Cumulative delay up to (and including) nth stage

  // Generate ROM of twiddle factors
  val twiddles_rom = Wire(Vec(numPointsDiv2, params.protoTwiddle.cloneType))
  (0 until numPointsDiv2).map(n => {
    twiddles_rom(n).real := Real[T].fromDouble( cos(2 * Pi / params.numPoints * n))
    twiddles_rom(n).imag := Real[T].fromDouble(-sin(2 * Pi / params.numPoints * n))
  })

  // FSM states for control logic
  val sIdle :: sComp :: sDone :: Nil = Enum(3)
  val state      = RegInit(sIdle)
  val state_next = Wire(state.cloneType)

  // Counter for control logic
  val cntr      = RegInit(0.U(log2Up(params.numPoints).W))
  val cntr_next = Wire(cntr.cloneType)

  // Instantiate and connect control signals of stages
  val sdf_stages = delayLog2s.zip(delays).zip(cumulative_delays).map {
    case ((delayLog2, delay), cumulative_delay) => {
      val stage = Module(new SDFStage(params, delay=delay, rom_shift=numStages - 1 - delayLog2))
      stage.io.twiddles_rom := twiddles_rom
      stage.io.cntr         := (cntr - cumulative_delay.U)(delayLog2, 0)
      stage.io.en           := io.in.fire()
      stage
    }
  }

  // Connect datapath of stages in series
  sdf_stages.map(_.io).foldLeft(RegNext(io.in.bits))((stg_in, stg_io) => {
    stg_io.in := stg_in
    stg_io.out
  })

  // Output interface connections
  // TODO: Do we need a Queue?
  io.out.bits  := sdf_stages.last.io.out
  io.out.valid := ShiftRegister(io.in.fire(), cumulative_delays.last + 1, resetData=false.B, en=true.B)
  io.in.ready  := io.out.ready

  // Controller FSM
  cntr_next  := cntr
  state_next := state

  switch (state) {
    is (sIdle) {
      when (io.in.fire()) { state_next := sComp }
    }
    is (sComp) {
      when (io.in.fire()) {
        cntr_next := cntr + 1.U
        when (cntr === (params.numPoints - 2).U) { state_next := sDone }
      }
    }
    is (sDone) {
      when      (io.in.fire())  { state_next := sComp }
      .elsewhen (io.out.fire()) { state_next := sIdle }
    }
  }

  when (state_next === sComp && state =/= sComp) {
    // Reset counter
    cntr_next := 0.U
  }

  cntr  := cntr_next
  state := state_next
}

/**
 * Bundle type as IO for direct FFT stage
 */
class SDFStageIO[T <: Data : Ring](params: FFTParams[T]) extends Bundle {
  // datapath
  val in  = Input(PacketBundle(1, params.protoIQ))
  val out = Output(PacketBundle(1, params.protoIQ))
  // control
  val twiddles_rom = Input(Vec(params.numPoints / 2, params.protoTwiddle.cloneType))
  val cntr         = Input(UInt(log2Up(params.numPoints).W))
  val en           = Input(Bool())

  override def cloneType: this.type = SDFStageIO(params).asInstanceOf[this.type]
}
object SDFStageIO {
  def apply[T <: Data : Ring](params: FFTParams[T]): SDFStageIO[T] = new SDFStageIO(params)
}

/**
 * Stage for SDF FFT
 *
 * Recursively instantiates smaller stages/DFTs based on the Cooley-Tukey algorithm decimation-in-time
 */
class SDFStage[T <: Data : Real : BinaryRepresentation](val params: FFTParams[T], val delay: Int, val rom_shift: Int = 0) extends Module {
  params.checkNumPointsPow2()
  require(isPow2(delay) && delay >= 1, "delay must be a power of 2 greater than or equal to 1")
  require(Seq("dit", "dif").contains(params.decimType), s"""Decimation type must either be dit or dif""")

  val io = IO(SDFStageIO(params))

  val inp = Wire(params.protoIQ.cloneType)
  val out = Wire(params.protoIQ.cloneType)

  // Apply twiddle factor at the input or output, depending on whether it's DIT or DIF
  if (params.decimType == "dit") {
    // Issue: using `inp := Mux(use_twiddle, io.in.iq(0) * twiddle, io.in.iq(0)` causes the following error:
    // can't create Mux with non-equivalent types dsptools.numbers.DspComplex@________ and dsptools.numbers.DspComplex@________
    when (io.cntr > delay.U) {
      inp := io.in.iq(0) * io.twiddles_rom((io.cntr - delay.U) << rom_shift.U)
    } .otherwise {
      inp := io.in.iq(0)
    }
    io.out.iq(0) := out
  } else {
    inp := io.in.iq(0)
    when (io.cntr < delay.U && io.cntr =/= 0.U) {
      io.out.iq(0) := out * io.twiddles_rom(io.cntr << rom_shift.U)
    } .otherwise {
      io.out.iq(0) := out
    }
  }

  val butterfly_outputs = Seq.fill(2)(Wire(params.protoIQ.cloneType))

  val load_input = io.cntr < delay.U
  val shift_in   = Mux(load_input, inp, butterfly_outputs(1))
  val shift_out  = ShiftRegister(shift_in, delay, en=io.en)

  Butterfly[T](Seq(shift_out, inp)).zip(butterfly_outputs).foreach { case (out_val, out_wire) => out_wire := out_val }

  out := Mux(load_input, shift_out, butterfly_outputs(0))

  io.out.pktStart := ShiftRegister(io.in.pktStart, delay, en=io.en)
  io.out.pktEnd   := ShiftRegister(io.in.pktEnd  , delay, en=io.en)
}

// Radix-n butterfly
object Butterfly {
  def apply[T <: Data : Real](in: Seq[DspComplex[T]]): Seq[DspComplex[T]] = {
    require(in.length == 2, "2-point DFT only for no defined twiddle type")
    Seq(in(0) + in(1), in(0) - in(1))
  }
  def apply[T <: Data : Real](in: Seq[DspComplex[T]], genTwiddle: DspComplex[T]): Seq[DspComplex[T]] = {
    in.length match {
      case 2 => apply(in)
      case _ => {
        val twiddlesSeq = (0 until in.length).map(n => {
          val twiddle_wire = Wire(genTwiddle.cloneType)
          twiddle_wire.real := Real[T].fromDouble( cos(2 * Pi / in.length * n))
          twiddle_wire.imag := Real[T].fromDouble(-sin(2 * Pi / in.length * n))
          twiddle_wire
        })
        Seq.tabulate(in.length)(k => {
          in.head + in.zipWithIndex.tail.map {
            case (inp, n) => inp * twiddlesSeq((k * n) % in.length)
          }.reduce(_ + _)
        })
      }
    }
  }
}
