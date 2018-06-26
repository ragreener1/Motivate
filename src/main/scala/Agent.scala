import scalaz._
import Scalaz._

import scala.annotation.tailrec
import scala.collection.mutable
/*
 Could psychological variables use a random value from a normal distribution mean = 1, s.d. = 0.25,
 then this value could be multiplied by the global importance, to generate the specific importance to the agent

 Should psychological factors be between 0-2?

 Should car / bike ownership be stored in agent?

 TODO: What parameters are needed, and how do they impact the functions of Agent
 */
/**
  * An agent for the model
  * @param subculture The demographic subculture the agent belongs to
  * @param neighbourhood The neighbourhood the agent lives in
  * @param commuteLength The JourneyType that signifies the commute distance
  * @param perceivedEffort The perceived effort of a mode of transport, for a given journey length
  *                        values should be between 0-1
  * @param weatherSensitivity How sensitive the agent is to weather (between 0-1)
  * @param autonomy How important the agent's norm is in deciding their mode of travel (between 0-1)
  * @param consistency How important the agent's habit is in deciding their mode of travel (between 0-1)
  * @param suggestibility How suggestible the agent is to the influence of their social network,
  *                       subculture, and neighbourhood (between 0-2):
  *                       1 = No adjustment
  *                       < 1 = Less important
  *                       > 1 = More important
  * @param socialConnectivity How connected the agent is to their social network
  * @param subcultureConnectivity How connected the agent is to their subculture
  * @param neighbourhoodConnectivity How connected the agent is to their neighbourhood
  * @param currentMode How the agent is currently going to work
  * @param habit The last commuting mode used
  * @param norm The preferred commuting mode
  */
class Agent(val subculture: Subculture,
            val neighbourhood: Neighbourhood,
            val commuteLength: JourneyType,
            val perceivedEffort: Map[JourneyType, Map[TransportMode, Float]],
            val weatherSensitivity: Float,
            val autonomy: Float,
            val consistency: Float,
            val suggestibility: Float,
            val socialConnectivity: Float,
            val subcultureConnectivity: Float,
            val neighbourhoodConnectivity: Float,
            val daysInHabitAverage: Int,
            var currentMode: TransportMode,
            var habit: TransportMode,
            var norm: TransportMode
           ) {
  var socialNetwork: mutable.Set[Agent] = mutable.Set()
  var neighbours: mutable.Set[Agent] = mutable.Set()

  /**
    * A daily log of chosen transport modes, the key is the day
    */
  val log: mutable.MutableList[TransportMode] = mutable.MutableList()

  /**
    * Updates the norm of the agent
    *
    * Uses the following function
    * maximise:
    * v * socialNetwork + w * neighbourhood + x * subcultureDesirability + y * norm + z * habit
    *
    * where:
    * v = socialConnectivity * suggestibility
    * w = neighbourhoodConnectivity * suggestibility
    * x = subcultureConnectivity * suggestibility
    * y = adherence
    * z = consistency
    */
  def updateNorm(): Unit = {
    val socialVals = countInSubgroup(socialNetwork, socialConnectivity * suggestibility)
    val neighbourVals = countInSubgroup(neighbours, neighbourhoodConnectivity * suggestibility)
    val subcultureVals = subculture.desirability.map { case(k, v) => (k, v * subcultureConnectivity * suggestibility)}
    val normVals: Map[TransportMode, Float] = Map(norm -> autonomy)
    val habitVals: Map[TransportMode, Float] = calculateMovingAverage(
      log.size - 1,
      Map(),
      weightFunction(log.size),
      weightFunction)
    val valuesToAdd: List[Map[TransportMode, Float]] = List(socialVals, neighbourVals, subcultureVals,normVals, habitVals)

    norm = valuesToAdd
      .reduce(_.unionWith(_)(_ + _)) // Add together vals with same key
      .maxBy(_._2) // find the max tuple by value
      ._1 // Get the key
  }

  /**
    * Calculates the percentages for each different travel mode in a group of agents, multiplied by some weight
    * @param v an iterable of agents
    * @param weight the weight to multiply by
    * @return a Map of TransportModes to weighted percentages
    */
  private def countInSubgroup(v: Iterable[Agent], weight: Float): Map[TransportMode, Float] =
    v.groupBy(_.habit).mapValues(_.size * weight / v.size)

  /**
    * Choose a new mode of travel
    *
    * maximise:
    * ((autonomy * norm) + (consistency * habit) + supportiveness)) * weather * effort
    *
    * @param weather the weather
    * @param changeInWeather whether there has been a change in the weather
    */
  def choose(weather: Weather, changeInWeather: Boolean): Unit = {
    habit = currentMode

    val normVal: Map[TransportMode, Float] = Map (norm -> autonomy)

    val habitVal: Map[TransportMode, Float] = calculateMovingAverage(
      log.size - 1,
      Map(),
      weightFunction(log.size),
      weightFunction)
        .mapValues(_ * consistency)

    val valuesToAdd: List[Map[TransportMode, Float]] = List(normVal, habitVal, neighbourhood.supportiveness)

    val intermediate: Map[TransportMode, Float] = valuesToAdd.reduce(_.unionWith(_)(_ + _))
    val effort = perceivedEffort(commuteLength).map { case (k, v) => (k, 1.0f - v) }

    // Cycling or walking in bad weather yesterday, strengthens your resolve to do so again
    // Taking a non-active mode weakens your resolve
    val resolve = if (!changeInWeather && (habit == Cycle || habit == Walk)) {
      0.1f
    } else if (!changeInWeather) {
      -0.1f
    } else {
      0.0f
    }

    val weatherModifier: Map[TransportMode, Float] = Map(
      Cycle -> (1.0f - weatherSensitivity + resolve),
      Walk -> (1.0f - weatherSensitivity + resolve),
      Car -> 1.0f,
      PublicTransport -> 1.0f
    )

    val valuesToMultiply: List[Map[TransportMode, Float]] = if (weather == Good) List(intermediate, effort) else List(intermediate, weatherModifier, effort)

    val newMode =
      valuesToMultiply
        .reduce(_.unionWith(_)(_ + _)) // Add together vals with same key
        .maxBy(_._2) // find the max tuple by value
        ._1 // Get the key

    log += newMode
    currentMode = newMode
  }

  @tailrec
  private def calculateMovingAverage (t: Int,
                                      accumulator: Map[TransportMode, Float],
                                      weight: Float,
                                      weightFunction: Float => Float): Map[TransportMode, Float] = t match {
    case 0 => accumulator.unionWith(Map(log.head -> 1.0f))(_ + _)
    case _ => calculateMovingAverage(t - 1,
      accumulator.unionWith(Map(log(t) -> weight))(_ + _),
      weight * (1 - weightFunction(log.size)),
      weightFunction)
  }

  private def weightFunction(n: Float) = 2 / (n + 1)
}
