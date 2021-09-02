package io.getquill.examples

import com.zaxxer.hikari.HikariDataSource
import io.getquill.context.ZioJdbc.QDataSource
import io.getquill.util.LoadConfig
import io.getquill.{JdbcContextConfig, Literal, PostgresZioJdbcContext}
import zio.console.putStrLn
import zio.Runtime
import io.getquill._

object PlainAppDataSource {

  object MyPostgresContext extends PostgresZioJdbcContext(Literal)
  import MyPostgresContext._

  case class Person(name: String, age: Int)

  def config = JdbcContextConfig(LoadConfig("testPostgresDB")).dataSource

  val zioConn =
    QDataSource.fromDataSource(new HikariDataSource(config)) >>> QDataSource.toConnection

  def main(args: Array[String]): Unit = {
    val people = quote {
      query[Person].filter(p => p.name == "Alex")
    }
    val qzio =
      MyPostgresContext.run(people)
        .tap(result => putStrLn(result.toString))
        .provideCustomLayer(zioConn)

    Runtime.default.unsafeRun(qzio)
    ()
  }
}
