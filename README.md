# stripe-scala, API for Stripe Using Scala

[![Build Status](https://travis-ci.org/mdedetrich/stripe-scala.svg?branch=master)](https://travis-ci.org/mdedetrich/stripe-scala)

stripe-scala is a wrapper over the [Stripe](https://stripe.com/) [REST api](https://stripe.com/docs/api/curl#intro). Unlike
[stripe-java](https://github.com/stripe/stripe-java), stripe-scala binds JSON response to the stripe object models (using Scala
case classes) and lets you create requests from typed case classes (rather than just using Java `Map<String,Object>`)

## Libraries Used
- [play-json](https://www.playframework.com/documentation/2.4.x/ScalaJson) for JSON (play-json provides compile time macros for
reading/writing JSON from/to scala case classes). It also provides a very powerful API for validating/querying JSON
- [dispatch](https://github.com/dispatch/reboot) for making HTTP requests
- [ficus](https://github.com/iheartradio/ficus) for providing config (via [typesafe-config](https://github.com/typesafehub/config))
- [enumeratum](https://github.com/lloydmeta/enumeratum) for providing typesafe enumerations on stripe enum models as well
- [scala-uri](https://github.com/NET-A-PORTER/scala-uri) for providing a URI DSL to generate query parameters for list operations
play-json formats for such models

stripe-scala was intentionally designed to use bare minimum external dependencies so its easier to integrate with scala codebases

## Installation

Currently, stripe-scala is in beta stage. The models are being completed, and quite a few endpoints have been coded but not the
entirety of the Stripe API is covered. 
It is being uploaded frequently as a SNAPSHOT on sonatype.

```scala
resolvers ++= Seq(
  Resolver.sonatypeRepo("snapshots")
)

libraryDependencies ++= Seq(
  "org.mdedetrich" %% "stripe-scala" % "0.1.0-SNAPSHOT"
)
```

## TODO for release
- [ ] Add all operations for all endpoints
- [x] Add tests
- [ ] Shade jawn/scala-uri/enumeratum if possible. These dependencies don't need to be exposed to users
- [ ] Document Stripe API with ScalaDoc
- [x] Figure out how to deal with list collections
- [x] Figure out how to deal with error handling
- [x] Provide default methods for models so that building them is nicer
- [x] Implement a single instance of all operation types to figure out if there are any potential issues
  - [x] get
  - [x] create
  - [x] update
  - [x] list
  - [x] delete
- [ ] Clean up/refactor code (still a lot of duplication)
- [ ] Webhooks/Events

## Usage
Stripe Api key and url endpoint are provided implicitly by using the `org.mdedetrich.stripe.ApiKey` and `org.mdedetrich.stripe.Endpoint`
types. The `org.mdedetrich.stripe.Config` object provides these keys through environment variables/system settings (see `application.conf`
for more details), although you can manually provide your own implicit `ApiKey` and `Endpoint` instances.

All base responses made are in the format of `Future[Try[T]]` where `T` is the model for the object being returned (i.e. creating a charge
will return a `Future[Try[Charges.Charge]]`). If there is an error in making the response that involves either invalid JSON or an error
in mapping the JSON to the models  `case class`, this will throw an exception which you need to catch
as a failed `Future` (it is by design that the models defined in stripe-scala are correct and that stripe does actually return valid JSON).

If there however is a checked error (such as an invalid API key) this will not throw an exception,
instead it will be contained within the `Try` monad (i.e. you will get a `scala.util.Failure`)

The second parameter for stripe POST requests (often named as create in stripe-scala) has an optional `idempotencyKey` which defaults
to `None`. You can specify a `IdempotencyKey` to make sure that you don't create duplicate POST requests with the same input.

stripe-scala provides `handle`/`handleIdempotent` functions which provides the typical way of dealing with stripe-errors.
It will attempt to retry the original request (using the `IdempotencyKey` to prevent duplicate side effects with `handleIdempotent`) for
errors which are deemed to be network related errors, else it will return a failed `Future`. If it
fails due to going over the retry limit, `handle`/`handleIdempotent` will also return a failed `Future` with `MaxNumberOfRetries`

```scala
import org.mdedetrich.stripe.v1.{Customers, handleIdempotent}

val customerInput: Customers.CustomerInput = ??? // Some customer input
val response: Future[Customers.Customer] = handleIdempotent(Customers.create(customerInput))
```

For the most part you will want to use `handleIdempotent`/`handle` however if you want
more fine grained control over potential errors then you can use the various `.create`/`.get` methods

### Default methods
The stripe object models in stripe-scala provide a `.default` method on the companion object which simplifies creating
the stripe models

```scala
import org.mdedetrich.stripe.v1.Customers._

val expMonth = 01
val expYear = 2020
val cardNumber = "4242424242424242"
val cvc = "536"

// Inefficient way
val source = Source.Card(expMonth,
                        expYear,
                        cardNumber,
                        None,
                        None,
                        None,
                        None,
                        None,
                        None,
                        None,
                        Option(cvc),
                        None,
                        None,
                        None
                      )

// Efficient way
val source2 = Source.Card
  .default(expMonth,expYear,cardNumber)
  .copy(cvc = Option(cvc))
```
The `.default` methods create an instance of the model with all of the `Optional` fields filled as `None`. Models
that have no `Optional` fields do not have a `.default` method.

### metadata

Stripe provides a metadata field which is available as an input field to most of the stripe objects. The metadata in stripe-scala
has a type of `Option[Map[String,String]]`. As you can see, the metadata is wrapped in an `Option`. This is to make working
with metadata easier. If the map for the metadata happens to empty, the metadata will be `None`.

### Timestamps

Stripe represents all of its timestamps as unix timestamp numbers (https://support.stripe.com/questions/what-timezone-does-the-dashboard-and-api-use)
however stripe-scala models store these timestamps as an `OffsetDateTime`. stripe-scala handles converting the unix timestamp
to `OffsetDateTime` and vice versa by using custom play-json writers/readers for JSON (`stripeDateTimeReads`/`stripeDateTimeWrites`) and
`stripeDateTimeParamWrites` for form parameters.

These functions are exposed publicly via the [package object](https://github.com/mdedetrich/stripe-scala/blob/master/src/main/scala/org/mdedetrich/stripe/v1/package.scala).

### Dealing with Card Errors
Since error messages from stripe are properly checked, dealing with errors like invalid CVC when adding a card are very easy to do.
Here is an example (we assume that you are using Play, but this can work with any web framework. Only `OK`,`BadRequest` and `Json.obj`
are Play related methods)

```scala

import org.mdedetrich.stripe.v1.Cards._
import org.mdedetrich.stripe.v1.Errors._
import org.mdedetrich.stripe.v1.{handleIdempotent,transformParam}

import play.api.mvc // Play related import

val expMonth = 01
val expYear = 2020
val cardNumber = "4000000000000127"
val cvc = "536"

val stripeCustomerId: String = ??? // Some stripe customer Id

val cardData = Cards.CardData.SourceObject
  .default(expMonth, expYear, cardNumber)
  .copy(cvc = Option(cvc))

val cardInput = Cards.CardInput.default(cardData)

val futureResponse = handleIdempotent(Cards.create(stripeCustomerId, cardInput)).recover {
  case Errors.Error.RequestFailed(CardError, _, Some(message), Some(param)) =>
    // We have a parameter, this usually means one of our fields is incorrect such as an invalid CVC
    BadRequest(Json.obj("message" -> List((transformParam(param), List(message))))
  case Errors.Error.RequestFailed(CardError, _, Some(message), None) =>
    // No parameter, usually means a more general error, such as a declined card
    BadRequest(Json.obj("message" -> message))
}.map { cardData =>
  Ok(Json.toJson(cardData))
}
```

We attempt to create a card, and if it fails due to a `CardError` we use the `.recover`
method on a `Future` with pattern matching to map it to a `BadRequest`. If the request passes, we simply wrap the
card data around an `Ok`. If we don't catch something of type `CardError` we let it propagate as a failed `Future`.

One thing to note is the `transformParam` function. Since scala-stripe uses camel case instead of stripe's snake case,
returned params for error messages from stripe will use snake case (i.e. "exp_month"). `transformParam` will convert
that to a "expMonth".

If you try and run the above code (remembering to implement `stripeCustomerId`) with that credit card number
in a test environment it should return an incorrect CVC, see [stripe testing](https://stripe.com/docs/testing)
for more info.

### List collection
stripe can return items in the form a of a list which has the following format

```json
{
  "object": "list",
  "url": "/v1/customers/35/sources",
  "has_more": false,
  "data": [
    {...},
    {...}
  ]
}
```

In stripe-scala, there is a base List collection at `org.mdedetrich.stripe.v1.Collections.List` with represents
the model for the list. Other stripe objects extend `org.mdedetrich.stripe.v1.Collections.List` to provide an implementation
of the object as a list collection, i.e. `BankAccountList` for `BankAccount`

### Formatting/Style Guide
The project uses scalafmt to enforce consistent Scala formatting. Please run scalafmt before commiting your
code to github (i.e. do `scalafmt` inside of sbt)

### Testing

The project has unit and integration tests. These can be run with:

```
sbt test
sbt it:test
```
