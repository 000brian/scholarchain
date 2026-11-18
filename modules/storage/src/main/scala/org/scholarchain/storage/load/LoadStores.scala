package org.scholarchain.storage.load

import java.nio.file.Path

import scala.util.control.NonFatal

import org.scholarchain.storage.StorageConfig
import org.scholarchain.storage.aws.{AwsClientSettings, AwsStorageEnvironment}
import org.scholarchain.storage.local.{
  InMemoryKeyValueStore,
  LocalFileObjectStore,
  RecordingBulkLoader
}
import org.scholarchain.storage.ports.{GraphBulkLoader, KeyValueStore, ObjectStore}

/** The stores one [[StorageLoadMain]] invocation uses: ordinary S3, the DynamoDB tables and the
  * Neptune bulk loader. Every store access of the loaders goes through these ports.
  */
trait LoadStores extends AutoCloseable {

  /** `local` or `aws`. */
  def kind: String
  def description: String
  def objects: ObjectStore
  def kv: KeyValueStore
  def bulkLoader: Either[String, GraphBulkLoader]

  /** Store facts for the run report (counts for local stores, retry counts for AWS). */
  def report(cfg: StorageConfig): ujson.Obj
}

object LoadStores {

  /** Local stores: ordinary S3 on the local file system under `root` (`<root>/<bucket>/<key>`,
    * never overwritten), in-memory DynamoDB tables (one invocation), and a bulk loader that records
    * jobs instead of calling Neptune. No AWS call, no network.
    */
  final class Local(root: Path, cfg: StorageConfig) extends LoadStores {
    val kind = "local"
    val description =
      "local: file-system object store, in-memory DynamoDB tables, recording bulk loader; no AWS call, no network"
    val objects: LocalFileObjectStore = new LocalFileObjectStore(root)
    val kvStore: InMemoryKeyValueStore = new InMemoryKeyValueStore(cfg.tableNames)
    def kv: KeyValueStore = kvStore
    val recorder: RecordingBulkLoader = new RecordingBulkLoader
    def bulkLoader: Either[String, GraphBulkLoader] = Right(recorder)
    def report(c: StorageConfig): ujson.Obj = ujson.Obj(
      "itemsPerTable" -> ujson.Obj.from(
        c.tableNames.toList.sorted.map(t => t -> ujson.Num(kvStore.itemCount(t).toDouble))
      ),
      "writesPerTable" -> ujson.Obj.from(
        c.tableNames.toList.sorted.map(t => t -> ujson.Num(kvStore.writeCount(t).toDouble))
      ),
      "bulkLoadJobsRecorded" -> recorder.jobs.size
    )
    def close(): Unit = ()
  }

  /** AWS stores through [[AwsStorageEnvironment]] (S3, DynamoDB, Neptune bulk loader). Names, the
    * Region and the Neptune endpoint come from configuration; credentials from the runtime role.
    * Construction makes no network call.
    */
  final class Aws(env: AwsStorageEnvironment) extends LoadStores {
    val kind = "aws"
    val description =
      "aws: S3, DynamoDB and the Neptune bulk loader through storage's AwsStorageEnvironment"
    def objects: ObjectStore = env.objects
    def kv: KeyValueStore = env.kv
    def bulkLoader: Either[String, GraphBulkLoader] = env.bulkLoader
    def report(c: StorageConfig): ujson.Obj = ujson.Obj("retries" -> env.retryReport)
    def close(): Unit = env.close()
  }

  /** SDK client bounds of the loaders: 10 s connect, 60 s socket, 60 s per call, one SDK attempt
    * (storage's bounded Retry is the only retry).
    */
  def clientSettings(cfg: StorageConfig): AwsClientSettings =
    AwsClientSettings(cfg.region, 10000, 60000, 60000, maxAttempts = 1)

  /** Builds the stores of `--stores`. `localRoot` is required for `local` and refused for `aws` by
    * the argument checks before this is called.
    */
  val default: (String, StorageConfig, Option[Path]) => Either[String, LoadStores] =
    (kind, cfg, localRoot) =>
      kind match {
        case "local" =>
          localRoot.toRight("--local-root is required for local stores").map(new Local(_, cfg))
        case "aws" =>
          try Right(new Aws(AwsStorageEnvironment.fromConfig(cfg, clientSettings(cfg), None)))
          catch {
            case NonFatal(e) =>
              Left(s"AWS stores could not be built: ${e.getClass.getSimpleName}")
          }
        case _ => Left("--stores must be local or aws")
      }
}
