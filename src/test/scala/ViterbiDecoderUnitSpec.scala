package modem

import org.scalatest.{FlatSpec, Matchers}

class ViterbiDecoderUnitSpec extends FlatSpec with Matchers {
  behavior of "Viterbi Decoder"

  val params = FixedCoding(
    k = 1,
    n = 2,
    K = 3,
    L = 2,
    O = 20,
    D = 5,
    genPolynomial = List(7, 6), // generator polynomial
    punctureEnable = false,
    punctureMatrix = List(6, 5), // Puncture Matrix
    CodingScheme = 0,
    fbPolynomial = List(0),
    tailBitingEn = false,
    tailBitingScheme = 0,
    softDecision = false
  )
  it should "Traceback" in {

    FixedViterbiDecoderTester(params) should be (true)
  }
}
