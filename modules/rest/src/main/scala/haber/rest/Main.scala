package haber.rest

import cats.effect.{IO, Resource, ResourceApp}
import haber.service.EmailStore

object Main extends ResourceApp.Forever:

  override def run(args: List[String]): Resource[IO, Unit] = for
    store <- Resource.eval(EmailStore.make[IO])
    routes = Routes(store)
    server <- Server(routes).make
  yield server
