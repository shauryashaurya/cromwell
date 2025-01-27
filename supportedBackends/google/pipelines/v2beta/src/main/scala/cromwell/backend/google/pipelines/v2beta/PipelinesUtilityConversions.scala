package cromwell.backend.google.pipelines.v2beta

import java.time.OffsetDateTime
import com.google.api.services.lifesciences.v2beta.model.{Accelerator, Disk, Event, Mount}
import cromwell.backend.google.pipelines.common.io.{DiskType, PipelinesApiAttachedDisk, PipelinesApiReferenceFilesDisk}
import cromwell.backend.google.pipelines.common.{GpuResource, MachineConstraints, PipelinesApiRuntimeAttributes}
import cromwell.core.ExecutionEvent
import cromwell.core.logging.JobLogger
import mouse.all._
import PipelinesUtilityConversions._

import scala.language.postfixOps

trait PipelinesUtilityConversions {
  def toAccelerator(gpuResource: GpuResource): Accelerator = new Accelerator().setCount(gpuResource.gpuCount.value.toLong).setType(gpuResource.gpuType.toString)
  def toMachineType(jobLogger: JobLogger)(attributes: PipelinesApiRuntimeAttributes): String =
    MachineConstraints.machineType(
      memory = attributes.memory,
      cpu = attributes.cpu,
      cpuPlatformOption = attributes.cpuPlatform,
      googleLegacyMachineSelection = attributes.googleLegacyMachineSelection,
      jobLogger = jobLogger,
    )
  def toMounts(disks: Seq[PipelinesApiAttachedDisk]): List[Mount] = disks.map(toMount).toList
  def toDisks(disks: Seq[PipelinesApiAttachedDisk]): List[Disk] = disks.map(toDisk).toList
  def toMount(disk: PipelinesApiAttachedDisk): Mount = {
    val mount = new Mount()
      .setDisk(disk.name)
      .setPath(disk.mountPoint.pathAsString)
    disk match {
      case _: PipelinesApiReferenceFilesDisk =>
        mount.setReadOnly(true)
      case _ =>
        mount
    }
  }

  def toDisk(disk: PipelinesApiAttachedDisk): Disk = {
    val googleDisk = new Disk()
      .setName(disk.name)
      .setType(disk.diskType |> toV2DiskType)
      .setSizeGb(disk.sizeGb)
    disk match {
      case refDisk: PipelinesApiReferenceFilesDisk =>
        googleDisk.setSourceImage(refDisk.image)
      case _ =>
        googleDisk
    }
  }

  def toExecutionEvent(actionIndexToEventType: Map[Int, String])(event: Event): ExecutionEvent = {
    val groupingFromAction = for {
      integerValue <- event.getActionId
      group <- actionIndexToEventType.get(integerValue)
    } yield group

    // There are both "Started pulling" and "Stopped pulling" events but these are confusing for metadata, especially on the
    // timing diagram. Create a single "Pulling <docker image>" grouping to absorb these events.
    def groupingFromPull: Option[String] = List("Started", "Stopped") flatMap { k =>
      Option(event.getDescription) collect { case d if d.startsWith(s"$k pulling") => "Pulling" + d.substring(s"$k pulling".length)}
    } headOption

    // See WX-1137. ContainerStoppedEvent descriptions contain the stderr, which may include 4-byte unicode
    // characters (typically emoji). Some databases have trouble storing these; replace them with the standard
    // "unknown character" unicode symbol.
    val name = Option(event.getContainerStopped) match {
      case Some(_) => cleanUtf8mb4(event.getDescription)
      case _ => event.getDescription
    }

    ExecutionEvent(
      name = name,
      offsetDateTime = OffsetDateTime.parse(event.getTimestamp),
      grouping = groupingFromAction.orElse(groupingFromPull)
    )
  }

  private def toV2DiskType(diskType: DiskType) = diskType match {
    case DiskType.HDD => "pd-standard"
    case DiskType.SSD => "pd-ssd"
    case DiskType.LOCAL => "local-ssd"
  }
}

object PipelinesUtilityConversions {

  implicit class EnhancedEvent(val event: Event) extends AnyVal {
    def getActionId: Option[Integer] = {
      if (event.getContainerKilled != null) {
        Option(event.getContainerKilled.getActionId)
      } else if (event.getContainerStarted != null) {
        Option(event.getContainerStarted.getActionId)
      } else if (event.getContainerStopped != null) {
        Option(event.getContainerStopped.getActionId)
      } else if (event.getUnexpectedExitStatus != null) {
        Option(event.getUnexpectedExitStatus.getActionId)
      } else {
        None
      }
    }
  }

  lazy val utf8mb4Regex = "[\\x{10000}-\\x{FFFFF}]"
  lazy val utf8mb3Replacement = "\uFFFD"  // This is the standard char for replacing invalid/unknown unicode chars
  def cleanUtf8mb4(in: String): String = {
    in.replaceAll(utf8mb4Regex, utf8mb3Replacement)
  }
}
