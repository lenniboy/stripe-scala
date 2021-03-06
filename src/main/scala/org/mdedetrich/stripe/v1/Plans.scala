package org.mdedetrich.stripe.v1

import java.time.OffsetDateTime
import com.typesafe.scalalogging.LazyLogging
import enumeratum._
import org.mdedetrich.playjson.Utils._
import org.mdedetrich.stripe.v1.DeleteResponses.DeleteResponse
import org.mdedetrich.stripe.{ApiKey, Endpoint, IdempotencyKey}
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

object Plans extends LazyLogging {

  sealed abstract class Interval(val id: String) extends EnumEntry {
    override val entryName = id
  }

  object Interval extends Enum[Interval] {

    val values = findValues

    case object Day extends Interval("day")

    case object Week extends Interval("week")

    case object Month extends Interval("month")

    case object Year extends Interval("year")
  }

  implicit val intervalFormats =
    EnumFormats.formats(Interval, insensitive = true)

  sealed abstract class Status(val id: String) extends EnumEntry {
    override val entryName = id
  }

  object Status extends Enum[Status] {

    val values = findValues

    case object Trialing extends Status("trialing")

    case object active extends Status("active")

    case object PastDue extends Status("past_due")

    case object Canceled extends Status("canceled")

    case object Unpaid extends Status("unpaid")
  }

  implicit val statusFormats = EnumFormats.formats(Status, insensitive = true)

  /**
    * @see https://stripe.com/docs/api#plan_object
    * @param id
    * @param amount              The amount in cents to be charged on the interval specified
    * @param created
    * @param currency            Currency in which subscription will be charged
    * @param interval            One of [[Interval.Day]], [[Interval.Week]], [[Interval.Month]] or
    *                            [[Interval.Year]]. The frequency with which a subscription
    *                            should be billed.
    * @param intervalCount       The number of intervals (specified in the [[interval]]
    *                            property) between each subscription billing. For example,
    *                            \[[interval]]=[[Interval.Month]] and [[intervalCount]]=3
    *                            bills every 3 months.
    * @param livemode
    * @param metadata            A set of key/value pairs that you can attach to a plan object.
    *                            It can be useful for storing additional information about the
    *                            plan in a structured format.
    * @param name                Display name of the plan
    * @param statementDescriptor Extra information about a charge for the customer’s
    *                            credit card statement.
    * @param trialPeriodDays     Number of trial period days granted when
    *                            subscribing a customer to this plan.
    *                            [[None]] if the plan has no trial period.
    */
  case class Plan(id: String,
                  amount: BigDecimal,
                  created: OffsetDateTime,
                  currency: Currency,
                  interval: Interval,
                  intervalCount: Long,
                  livemode: Boolean,
                  metadata: Option[Map[String, String]],
                  name: String,
                  statementDescriptor: Option[String],
                  trialPeriodDays: Option[Long])

  object Plan {
    def default(id: String,
                amount: BigDecimal,
                created: OffsetDateTime,
                currency: Currency,
                interval: Interval,
                intervalCount: Long,
                livemode: Boolean,
                name: String): Plan = Plan(
      id,
      amount,
      created,
      currency,
      interval,
      intervalCount,
      livemode,
      None,
      name,
      None,
      None
    )
  }

  implicit val planReads: Reads[Plan] = (
    (__ \ "id").read[String] ~
      (__ \ "amount").read[BigDecimal] ~
      (__ \ "created").read[OffsetDateTime](stripeDateTimeReads) ~
      (__ \ "currency").read[Currency] ~
      (__ \ "interval").read[Interval] ~
      (__ \ "interval_count").read[Long] ~
      (__ \ "livemode").read[Boolean] ~
      (__ \ "metadata").readNullableOrEmptyJsObject[Map[String, String]] ~
      (__ \ "name").read[String] ~
      (__ \ "statement_descriptor").readNullable[String] ~
      (__ \ "trial_period_days").readNullable[Long]
  ).tupled.map((Plan.apply _).tupled)

  implicit val planWrites: Writes[Plan] = Writes(
    (plan: Plan) =>
      Json.obj(
        "id"                   -> plan.id,
        "object"               -> "plan",
        "amount"               -> plan.amount,
        "created"              -> Json.toJson(plan.created)(stripeDateTimeWrites),
        "currency"             -> plan.currency,
        "interval"             -> plan.interval,
        "interval_count"       -> plan.intervalCount,
        "livemode"             -> plan.livemode,
        "metadata"             -> plan.metadata,
        "name"                 -> plan.name,
        "statement_descriptor" -> plan.statementDescriptor,
        "trial_period_days"    -> plan.trialPeriodDays
    ))

  /**
    * @see https://stripe.com/docs/api#create_plan
    * @param id                  Unique string of your choice that will be used
    *                            to identify this plan when subscribing a customer.
    *                            This could be an identifier like “gold” or a
    *                            primary key from your own database.
    * @param amount              A positive integer in cents (or 0 for a free plan)
    *                            representing how much to charge (on a recurring basis).
    * @param currency            3-letter ISO code for currency.
    * @param interval            Specifies billing frequency. Either [[Interval.Day]],
    *                            [[Interval.Week]], [[Interval.Month]] or [[Interval.Year]].
    * @param name                Name of the plan, to be displayed on invoices and in
    *                            the web interface.
    * @param intervalCount       The number of intervals between each subscription
    *                            billing. For example, [[interval]]=[[Interval.Month]]
    *                            and [[intervalCount]]=3 bills every 3 months. Maximum of
    *                            one year interval allowed (1 year, 12 months, or 52 weeks).
    * @param metadata            A set of key/value pairs that you can attach to a plan object.
    *                            It can be useful for storing additional information about
    *                            the plan in a structured format. This will be unset if you
    *                            POST an empty value.
    * @param statementDescriptor An arbitrary string to be displayed on your
    *                            customer’s credit card statement. This may be up to
    *                            22 characters. As an example, if your website is
    *                            RunClub and the item you’re charging for is your
    *                            Silver Plan, you may want to specify a [[statementDescriptor]]
    *                            of RunClub Silver Plan. The statement description may not include `<>"'`
    *                            characters, and will appear on your customer’s statement in
    *                            capital letters. Non-ASCII characters are automatically stripped.
    *                            While most banks display this information consistently,
    *                            some may display it incorrectly or not at all.
    * @param trialPeriodDays     Specifies a trial period in (an integer number of)
    *                            days. If you include a trial period, the customer
    *                            won’t be billed for the first time until the trial period ends.
    *                            If the customer cancels before the trial period is over,
    *                            she’ll never be billed at all.
    * @throws StatementDescriptorTooLong          - If [[statementDescriptor]] is longer than 22 characters
    * @throws StatementDescriptorInvalidCharacter - If [[statementDescriptor]] has an invalid character
    */
  case class PlanInput(id: String,
                       amount: BigDecimal,
                       currency: Currency,
                       interval: Interval,
                       name: String,
                       intervalCount: Option[Long],
                       metadata: Option[Map[String, String]],
                       statementDescriptor: Option[String],
                       trialPeriodDays: Option[Long]) {
    statementDescriptor match {
      case Some(sD) if sD.length > 22 =>
        throw StatementDescriptorTooLong(sD.length)
      case Some(sD) if sD.contains("<") =>
        throw StatementDescriptorInvalidCharacter("<")
      case Some(sD) if sD.contains(">") =>
        throw StatementDescriptorInvalidCharacter(">")
      case Some(sD) if sD.contains("\"") =>
        throw StatementDescriptorInvalidCharacter("\"")
      case Some(sD) if sD.contains("\'") =>
        throw StatementDescriptorInvalidCharacter("\'")
      case _ =>
    }
  }

  object PlanInput {
    def default(id: String, amount: BigDecimal, currency: Currency, interval: Interval, name: String): PlanInput =
      PlanInput(
        id,
        amount,
        currency,
        interval,
        name,
        None,
        None,
        None,
        None
      )
  }

  implicit val planInputReads: Reads[PlanInput] = (
    (__ \ "id").read[String] ~
      (__ \ "amount").read[BigDecimal] ~
      (__ \ "currency").read[Currency] ~
      (__ \ "interval").read[Interval] ~
      (__ \ "name").read[String] ~
      (__ \ "interval_count").readNullable[Long] ~
      (__ \ "metadata").readNullableOrEmptyJsObject[Map[String, String]] ~
      (__ \ "statement_descriptor").readNullable[String] ~
      (__ \ "trial_period_days").readNullable[Long]
  ).tupled.map((PlanInput.apply _).tupled)

  implicit val planInputWrites: Writes[PlanInput] = Writes(
    (planInput: PlanInput) =>
      Json.obj(
        "id"                   -> planInput.id,
        "amount"               -> planInput.amount,
        "currency"             -> planInput.currency,
        "interval"             -> planInput.interval,
        "name"                 -> planInput.name,
        "interval_count"       -> planInput.intervalCount,
        "metadata"             -> planInput.metadata,
        "statement_descriptor" -> planInput.statementDescriptor,
        "trial_period_days"    -> planInput.trialPeriodDays
    ))

  def create(planInput: PlanInput)(idempotencyKey: Option[IdempotencyKey] = None)(
      implicit apiKey: ApiKey,
      endpoint: Endpoint): Future[Try[Plan]] = {
    val postFormParameters: Map[String, String] = {
      Map(
        "id"                   -> Option(planInput.id.toString),
        "amount"               -> Option(planInput.amount.toString()),
        "currency"             -> Option(planInput.currency.iso.toLowerCase),
        "interval"             -> Option(planInput.interval.id.toString),
        "name"                 -> Option(planInput.name),
        "interval_count"       -> planInput.intervalCount.map(_.toString),
        "statement_descriptor" -> planInput.statementDescriptor,
        "trial_period_days"    -> planInput.trialPeriodDays.map(_.toString)
      ).collect {
        case (k, Some(v)) => (k, v)
      }
    } ++ mapToPostParams(planInput.metadata, "metadata")

    logger.debug(s"Generated POST form parameters is $postFormParameters")

    val finalUrl = endpoint.url + "/v1/plans"

    createRequestPOST[Plan](finalUrl, postFormParameters, idempotencyKey, logger)
  }

  def get(id: String)(implicit apiKey: ApiKey, endpoint: Endpoint): Future[Try[Plan]] = {
    val finalUrl = endpoint.url + s"/v1/plans/$id"

    createRequestGET[Plan](finalUrl, logger)
  }

  def delete(id: String)(idempotencyKey: Option[IdempotencyKey] = None)(
      implicit apiKey: ApiKey,
      endpoint: Endpoint): Future[Try[DeleteResponse]] = {
    val finalUrl = endpoint.url + s"/v1/plans/$id"

    createRequestDELETE(finalUrl, idempotencyKey, logger)
  }

  /**
    * @see https://stripe.com/docs/api#list_plans
    * @param created       A filter on the list based on the object
    *                      [[created]] field. The value can be a string
    *                      with an integer Unix timestamp, or it can be a
    *                      dictionary with the following options:
    * @param endingBefore  A cursor for use in pagination. [[endingBefore]] is an
    *                      object ID that defines your place in the list.
    *                      For instance, if you make a list request and
    *                      receive 100 objects, starting with obj_bar,
    *                      your subsequent call can include [[endingBefore]]=obj_bar
    *                      in order to fetch the previous page of the list.
    * @param limit         A limit on the number of objects to be returned.
    *                      Limit can range between 1 and 100 items.
    * @param startingAfter A cursor for use in pagination. [[startingAfter]] is
    *                      an object ID that defines your place in the list.
    *                      For instance, if you make a list request and receive 100
    *                      objects, ending with obj_foo, your subsequent call
    *                      can include [[startingAfter]]=obj_foo in order to
    *                      fetch the next page of the list.
    */
  case class PlanListInput(created: Option[ListFilterInput],
                           endingBefore: Option[String],
                           limit: Option[Long],
                           startingAfter: Option[String])

  object PlanListInput {
    def default: PlanListInput = PlanListInput(
      None,
      None,
      None,
      None
    )
  }

  case class PlanList(override val url: String,
                      override val hasMore: Boolean,
                      override val data: List[Plan],
                      override val totalCount: Option[Long])
      extends Collections.List[Plan](url, hasMore, data, totalCount)

  object PlanList extends Collections.ListJsonMappers[Plan] {
    implicit val customerListReads: Reads[PlanList] =
      listReads.tupled.map((PlanList.apply _).tupled)

    implicit val customerWrites: Writes[PlanList] = listWrites
  }

  def list(planListInput: PlanListInput, includeTotalCount: Boolean)(implicit apiKey: ApiKey,
                                                                     endpoint: Endpoint): Future[Try[PlanList]] = {
    val finalUrl = {
      import com.netaporter.uri.dsl._
      val totalCountUrl =
        if (includeTotalCount)
          "/include[]=total_count"
        else
          ""

      val baseUrl = endpoint.url + s"/v1/customers$totalCountUrl"

      val created: com.netaporter.uri.Uri = planListInput.created match {
        case Some(createdInput) =>
          listFilterInputToUri(createdInput, baseUrl, "created")
        case None => baseUrl
      }

      (created ?
        ("ending_before"  -> planListInput.endingBefore) ?
        ("limit"          -> planListInput.limit.map(_.toString)) ?
        ("starting_after" -> planListInput.startingAfter)).toString()
    }

    createRequestGET[PlanList](finalUrl, logger)
  }
}
