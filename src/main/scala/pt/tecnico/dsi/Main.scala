package pt.tecnico.dsi

import java.io.File

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import scala.collection.JavaConverters._
import scala.collection.immutable.{ListMap, SortedMap}
import io.circe._
import io.circe.parser._
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.{ExternalDocumentation, OpenAPI, Operation, PathItem}
import io.swagger.v3.oas.models.parameters._
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media._
import io.swagger.v3.oas.models.responses.{ApiResponse, ApiResponses}
import io.swagger.v3.oas.models.servers.{Server, ServerVariable, ServerVariables}
import org.http4s.Status

import scala.collection.mutable

case class Detail(parameters: Option[List[Parameter]], example: Option[Json]) {
  def parametersNotIn(in: String): Option[List[Parameter]] = parameters.map(_.filter(_.getIn != in)).filter(_.nonEmpty)
  def parametersIn(in: String): List[Parameter] = parameters.map(_.filter(_.getIn == in)).filter(_.nonEmpty).getOrElse(List.empty)
  def pathParameters: List[Parameter] = parametersIn("path")
  def queryParameters: List[Parameter] = parametersIn("query")
  def bodyParameters: List[Parameter] = parametersIn("body")
  def headerParameters: List[Parameter] = parametersIn("header")
}
case class Endpoint(method: HttpMethod, path: String, operation: Operation)

object Main extends App {
  def sectionName(section: Element): Option[String] = for {
    s <- Option(section)
    header <- Option(s.selectFirst("> h1,h2"))
  } yield header.ownText()

  def extractEndpointsElement(startingSections: Elements): ListMap[String, Seq[Element]] = {
    val elements = startingSections.asScala.filter { section =>
      sectionName(section) match {
        case Some(name) if name.toLowerCase.contains("deprecated") || name.toLowerCase.contains("obsolete") =>
          println(s"\tNot adding '$name'")
          false
        case _ => true
      }
    }.flatMap { section =>
      val endpoints = section.select("> .detail-control")
      if (endpoints.size() > 0) {
        val name = section.selectFirst("> h1,h2").ownText().replaceAll(" \\([^)]+\\)", "")
        Map(name -> endpoints.asScala)
      } else {
        extractEndpointsElement(section.select("> .section"))
      }
    }
    ListMap(elements:_*)
  }

  def parseParametersTable(table: Element): List[Parameter] = {
    table.select("tbody > tr").asScala
      .map(_.select("td").asScala.toList)
      .collect { case List(name, in, typeElement, description) =>
        val nameRegex = "([^ ]+)( \\(Optional\\))?".r
        val (parsedName, required) = name.text() match {
          case nameRegex(n, optional) => (n, Option(optional).isEmpty)
          case n => (n, true)
        }
        val schema: Schema[_] = typeElement.ownText().toLowerCase() match {
          case "enum" =>
            val values = Option(description.selectFirst(":matches(^Example formats are: )"))
              .fold[List[String]](List.empty)(_.select(".pre").asScala.map(_.ownText()).toList)

            if (values.isEmpty) {
              println("\t" * 3 + s"GOT ENUM '$parsedName' but could not get the values")
              new StringSchema()
            } else {
              new StringSchema()._enum(values.asJava)
            }
          case "array" | "list" => new ArraySchema().items(new StringSchema()) // TODO: this isn't always true
          case "bool" => new BooleanSchema()
          case "datestamp" => new DateTimeSchema()
          case "dict" => new ObjectSchema()
          case "float" => new NumberSchema()
          case "none" => null
          case t @ ("domainname" | "hostname" | "uuid") => new StringSchema().format(t)
          case tpe => new Schema().`type`(tpe)
        }

        new Parameter()
          .name(parsedName)
          .in(in.text())
          .description(description.text())
          .required(required)
          .schema(schema)
      }.toList
  }

  def extractDetail(`type`: String, apiDetail: Element): Detail = {
    val headers = apiDetail.select(".section > h4, .section > h3").asScala

    val section = headers.find(_.ownText().matches(s"^${`type`}$$"))
    val parameters: Option[Element] = section
      .orElse(headers.find(_.ownText().matches(s"^${`type`} Parameters$$")))
      .flatMap(_.parent().select("table.docutils").asScala.headOption)

    val example: Option[Json] = headers.find(_.ownText().matches(s"^${`type`} Example$$"))
      .orElse(section)
      .flatMap(_.parent().select("pre").asScala.headOption)
      .map(e => parse(e.text().replaceAll("\"([^ ]+) \"", "\"$1\""))) // If the json includes json encoded documents this might break them
      .flatMap(_.right.toOption)

    Detail(parameters.map(parseParametersTable), example)
  }

  def getCodes(apiDetail: Element): Seq[Status] = {
    val codeRegex = "([2345]\\d{2})".r
    val status: mutable.Seq[Status] = for {
      element <- apiDetail.getElementsMatchingOwnText("^(Normal|Error) (R|r)esponse (C|c)odes:".r.pattern).asScala
      textMatch <- codeRegex.findAllMatchIn(element.ownText())
      text = textMatch.group(1)
      status <- Status.fromInt(text.toInt).toOption
    } yield status

    if (status.isEmpty) {
      apiDetail.select(s"#Success, #Error").select("> table.docutils > tbody > tr").asScala
        .map(_.select("td").asScala.toList)
        .collect { case List(code, reason) =>
          codeRegex.findFirstIn(code.text()).map { codeString =>
            Status(codeString.toInt, reason.ownText())
          }
        }.flatten
    } else status
  }

  def extractEndpoint(apiDocumentationBaseURL: String, groupName: String, endpoint: Element): Endpoint = {
    val Seq(extractedPath, summary, _*) = endpoint.select(".endpoint-container .row").asScala.map(_.text())
    val method = HttpMethod.valueOf(endpoint.select(".operation .label").text().toUpperCase)
    val path = extractedPath.replaceAll("} /", "}/")
    val apiDetail = endpoint.nextElementSibling()

    val description = apiDetail.children().not(".section, :matchesOwn(^(Normal|Error) response codes:)")
      .asScala.foldLeft("")(_ + _.outerHtml())

    val operation = new Operation()
      .operationId(summary.split(" ").map(_.capitalize).mkString)
      .summary(summary)
      .description(description)
      .addTagsItem(groupName)
      .externalDocs(new ExternalDocumentation()
        .url(apiDocumentationBaseURL + endpoint.select(".operation a").attr("href")))

    val requestDetail = extractDetail("Request", apiDetail)
    for (parameters <- requestDetail.parametersNotIn("body")) {
      operation.setParameters(parameters.asJava)
    }
    requestDetail.example.foreach { json =>
      operation.setRequestBody(new RequestBody()
        .required(true)
        .content(new Content()
          .addMediaType("application/json", new MediaType()
            .example(json.spaces2)
            .schema{
              val schema = new ObjectSchema()
              for (parameter <- requestDetail.bodyParameters) {
                schema.addProperties(parameter.getName, parameter.getSchema)
              }
              schema
            })))
    }

    val responseDetail = extractDetail("Response", apiDetail)
    val responses = new ApiResponses()
    getCodes(apiDetail).foreach { status =>
      val response = new ApiResponse().description(status.reason)
      for (parameter <- responseDetail.headerParameters) {
        response.addHeaderObject(parameter.getName, new Header()
          .description(parameter.getDescription)
          .schema(parameter.getSchema))
      }

      responseDetail.example.filter(_ => status.isSuccess).foreach { json =>
        response.setContent(new Content()
          .addMediaType("application/json", new MediaType()
            .schema{
              val schema = new ObjectSchema()
              for (parameter <- responseDetail.bodyParameters) {
                schema.addProperties(parameter.getName, parameter.getSchema)
              }
              schema.example(json.spaces2)
            }))
      }
      responses.addApiResponse(status.code.toString, response)
    }
    if (!responses.isEmpty) operation.setResponses(responses)

    Endpoint(method, path, operation)
  }

  def createOpenAPISpecification(name: String, version: Int): OpenAPI = {
    val apiDocumentationBaseURL = s"http://developer.openstack.org/api-ref/$name/${if (version > 1) s"v$version/" else ""}"
    val oai = new OpenAPI()
      .info(new Info()
        .title(s"Openstack ${name.capitalize} v$version")
        .version(version.toString))
      .externalDocs(new ExternalDocumentation()
        .description("read more here")
        .url(apiDocumentationBaseURL))

    oai.addServersItem(new Server()
      .url("https://{address}:{port}/{basePath}")
      .variables(new ServerVariables()
        .addServerVariable("address", new ServerVariable()
          .description(s"The address where $name is being served.")
          ._default(s"$name.local"))
        .addServerVariable("port", new ServerVariable()
          .description(s"The port where $name is being served.")
          ._default(s"443"))
        .addServerVariable("basePath", new ServerVariable()
          .description(s"The basePath where $name is being served.")
          ._default(s"v$version")))
    )

    val jsoup = Jsoup.parse(new File(s"./$name-v$version.formatted.html"), "UTF-8")
    val sections = jsoup.select(".docs-body").select("> .section")
    val endpointGroups = extractEndpointsElement(sections)
    println(s"${name.toUpperCase} has ${endpointGroups.values.foldLeft(0)(_ + _.size)} endpoints")
    endpointGroups.foreach { case (groupName, endpointsFlat) =>
      println(s"\t$groupName (${endpointsFlat.size} endpoints)")
      val endpointsPerPath = endpointsFlat
        .map(extractEndpoint(apiDocumentationBaseURL, groupName, _))
        .groupBy(_.path)
      // The previous groupBy changed the ordering of the paths. So we enforce it.
      SortedMap(endpointsPerPath.toSeq:_*).foreach { case (path, endpoints) =>
          //println("\t" * 2 + path)
          val pathItem = new PathItem()
          endpoints.foreach { case Endpoint(method, _, operation) =>
            //println("\t" * 3 + s"$method = ${operation.getSummary}")
            pathItem.operation(method, operation)
          }
          oai.path(path, pathItem)
        }
    }
    oai
  }

  val writer = new ObjectMapper(new YAMLFactory())
    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    .writer(new DefaultPrettyPrinter())

  ListMap(
    "image" -> 2,
    "dns" -> 1,
    "identity" -> 3,
    "block-storage" -> 3,
    "compute" -> 1,
    "network" -> 2,
  ).foreach { case (name, version) =>
    val oai = createOpenAPISpecification(name, version)
    val file = new File(s"openapi.$name-v$version.yaml")
    file.delete()
    writer.writeValue(file, oai)
  }
}
