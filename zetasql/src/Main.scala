package dev.mauch.zetasql

import com.google.zetasql.*
import parser.ASTNodes

object Main {
  def mainy(args: Array[String]): Unit = {
    println("Hello, world!")

    //JniChannelProvider.load()
    val parsed = Parser.parseStatement("SELECT name FROM users", new LanguageOptions())
    val q = parsed.asInstanceOf[ASTNodes.ASTQueryStatement]
    //parsed.accept(null)
    println(parsed)

  }
}
