package mesosphere.marathon
package api.v3

import java.net.URI

import akka.event.EventStream
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.{AppHelpers, AppNormalization}
import mesosphere.marathon.api.{AuthResource, RestResource}
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.experimental.repository.TemplateRepository
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{AppDefinition, PathId}
import org.glassfish.jersey.server.ManagedAsync
import play.api.libs.json.{JsString, Json}

import scala.async.Async._
import scala.concurrent.ExecutionContext
import scala.util.Success

@Path("v3/templates")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
class TemplatesResource @Inject() (
    templateRepository: TemplateRepository,
    eventBus: EventStream,
    val config: MarathonConf,
    launchQueue: LaunchQueue,
    pluginManager: PluginManager)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext,
    val mat: Materializer) extends RestResource with AuthResource with StrictLogging {

  import AppHelpers._
  import Normalization._

  private implicit lazy val appDefinitionValidator = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)

  private val normalizationConfig = AppNormalization.Configuration(
    config.defaultNetworkName.toOption,
    config.mesosBridgeName())

  private implicit val validateAndNormalizeApp: Normalization[raml.App] =
    appNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())

  // TODO(ad): this whole API idea of:
  //           - GET v3/templates/{id}
  //           - GET v3/templates/{id}/versions
  //           - GET v3/templates/{id}/latest
  //           is IMHO not very comfortable. I'd rather go towards "browsing tree" approach e.g.
  //
  // TODO(ad): new API proposal:
  //           - GET v3/templates/ - returns *all* children of `/` with type (folder/service) and url
  //           - GET v3/templates/foo - returns children for `/foo`. If '/foo' is folder, return list of service urls,
  //             otherwise, list of versions of `/foo`
  //           - GET v3/templates/{id} - tree traversal where {id} has arbitrary nesting
  //           - GET v3/templates/foo/s12d234d2 - content of template `/foo` in version `s12d234d2`
  //
  // TODO(ad): add checking resource authorization for all calls
  // TODO(ad): prevent users from modifying same pathId concurrently? Do we need it?
  // TODO(ad): filter created pathIds for key words like `latest` etc
  // TODO(ad): don't delete templates if there are instances referencing it

  @POST
  @ManagedAsync
  def create(
    body: Array[Byte],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val rawApp = Raml.fromRaml(Json.parse(body).as[raml.App].normalize)
      val app = validateOrThrow(rawApp)
      val version = await(templateRepository.create(app))

      Response
        .created(new URI(app.id.toString))
        .entity(jsonObjString("version" -> JsString(version)))
        .build()
    }
  }

  @GET
  @Path("""{id:.+}/latest""")
  def latest(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      val templateId = PathId(id)

      val versions: Seq[String] = templateRepository.children(templateId).getOrElse(throw new TemplateNotFoundException(templateId))
      if (versions.isEmpty) {
        Response.ok().entity(jsonArrString()).build()
      } else {
        // Collect all existing templates and order them by the version timestamp
        val templates = versions
          .map{ v =>
            templateRepository.read(AppDefinition(id = templateId), version = v) match {
              case Success(t) => t
              case scala.util.Failure(ex) =>
                logger.error(s"Failed to load template $templateId/$v from the repository", ex)
                throw new TemplateNotFoundException(templateId, Some(v))
            }
          }
          .sortBy(t => t.version)
          .reverse

        val template = templates.headOption.getOrElse(throw new TemplateNotFoundException(templateId))

        Response
          .ok()
          .entity(jsonObjString("template" -> template))
          .build()
      }
    }
  }

  @GET
  @Path("{id:.+}/versions")
  def versions(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val templateId = PathId(id)
      val versions = templateRepository.children(templateId).getOrElse(throw new TemplateNotFoundException(templateId))

      Response
        .ok()
        .entity(jsonObjString("versions" -> versions))
        .build()

    }
  }

  @GET
  @Path("{id:.+}/versions/{version}")
  def version(
    @PathParam("id") id: String,
    @PathParam("version") version: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val templateId = PathId(id)
      val template = templateRepository.read(AppDefinition(id = templateId), version).getOrElse(throw new TemplateNotFoundException(templateId, Some(version)))

      checkAuthorization(ViewRunSpec, template)

      Response
        .ok()
        .entity(jsonObjString("template" -> template))
        .build()
    }
  }

  @SuppressWarnings(Array("all"))
  @DELETE
  @Path("""{id:.+}""")
  def delete(
    @PathParam("id") id: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val templateId = PathId(id)

      await(templateRepository.delete(templateId))

      Response
        .ok()
        .build()
    }
  }

  @SuppressWarnings(Array("all"))
  @DELETE
  @Path("{id:.+}/versions/{version}")
  def delete(
    @PathParam("id") id: String,
    @PathParam("version") version: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val templateId = PathId(id)

      await(templateRepository.delete(templateId, version))

      Response
        .ok()
        .build()
    }
  }

  /**
    * ====================== EXPERIMENTAL ======================
    *
    * All the code in this class should be considered experimental but this method especially. Here, we mix orchestration
    * logic (starting instances) with template concerns (saving them). While, this kind of endpoint can be very handy, I
    * don't think we should have it underneath `/templates`.
    *
    * @param body
    * @param req
    * @param asyncResponse
    */
  @POST
  @ManagedAsync
  @Path("start")
  def start(
    body: Array[Byte],
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))

      val rawApp = Raml.fromRaml(Json.parse(body).as[raml.App].normalize)
      val app = validateOrThrow(rawApp)
      val version = await(templateRepository.create(app))

      logger.info(s"Scheduling ${app.instances} instance(s) for the ${app.id}")
      // Ideally, LaunchQueue is an implementation detail and an orchestrator only knows
      // the instance tracker: instanceTracker.schedule(Instance.scheduled(app))
      // We await the result here since that guaranties that the instance is persisted.
      await(launchQueue.add(app, count = app.instances))

      Response
        .created(new URI(app.id.toString))
        .entity(jsonObjString("version" -> JsString(version)))
        .build()
    }
  }
}