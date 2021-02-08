package noe.workspace

import groovy.util.logging.Slf4j
import noe.common.DefaultProperties
import noe.common.NoeContext
import noe.common.utils.Cleaner
import noe.common.utils.Hudson
import noe.common.utils.JBFile
import noe.common.utils.Library
import noe.common.utils.PathHelper
import noe.common.utils.Platform
import noe.server.ServerAbstract
import noe.server.ServerController

@Slf4j
abstract class WorkspaceAbstract implements IWorkspace {
  static AntBuilder ant /// AntBuilder instance that was provided by Hudson
  static Platform platform /// platform of the machine, the test runs on
  ServerController serverController /// contains all server abstraction
  NoeContext context /// test context - ews, ews-rpm, ...
  String basedir /// base directory, typically the workspace directory
  Boolean skipInstall /// Skip built-in installation workspace process
  Boolean deleteWorkspace /// Delete workskpace after runnig the tests?
  static boolean testAppsInstalled = false
  static boolean workspaceSafeCleaned = false

  /**
   * Constructor
   */
  WorkspaceAbstract() {
    this.ant = new AntBuilder()
    this.ant.property(environment: 'env')
    this.platform = new Platform()
    this.serverController = ServerController.getInstance()
    this.context = NoeContext.forCurrentContext()
    this.basedir = WorkspaceAbstract.retrieveBaseDir()
    createBasedir()
    this.skipInstall = Boolean.valueOf(Library.getUniversalProperty('workspace.install.skip', 'false'))
    this.deleteWorkspace = Boolean.valueOf(Library.getUniversalProperty('workspace.delete', 'false'))
    File basedirFile = new File(basedir)
    if (!basedirFile.exists()) {
      log.debug('Workspace basedir creation: ' + (JBFile.mkdir(basedirFile) ? "SUCCESS" : "FAIL"))
    } else if ( DefaultProperties.CLEAN_WORKSPACE_AT_START && !workspaceSafeCleaned && !this.skipInstall ) {
      if ( !Cleaner.cleanDirectoryBasedOnRegex(basedirFile) ) {
        workspaceSafeCleaned = true
      }
    }

    // TODO: Are the paths valid?
    // TODO: review basedir
    def invalidPaths = Library.validatePaths([basedir])
    if (!invalidPaths.isEmpty()) {
      // TODO LP How to better handle this?
      throw new RuntimeException('Invalid paths' + invalidPaths.toString())
    }
  }

  /**
   * Prepare workspace
   * Start services, do configurations etc.
   *
   * Do serverControler backup
   *
   * ---------------------------------------------
   * IMPORTANT backup must be called on the END of prepare methods in all children classes
   * ---------------------------------------------
   */
  def prepare() {
    serverController.backup()
  }

  /**
   * Clean the mess
   *
   * @param cleanController defines whether the server controller should be cleaned or not, it is cleaned by default.
   */
  def destroy(boolean cleanController = true) {
    serverController.killAllInSystem()
    serverController.stopAllServers()

    if (platform.isWindows()) {
      serverController.uninstallWindowsServices()
    }

    if (cleanController) {
      serverController.clean()
    }

    File tmpCertDir = new File(platform.tmpDir, "sslCerts")
    if (tmpCertDir.exists()) {
      JBFile.delete(tmpCertDir)
    }
  }

  String createBasedir() {
    boolean createdBasedir = new File(basedir).mkdirs()
    if (createdBasedir) {
      log.debug("The ${basedir} has been successfully created")
    }
    return basedir
  }

  static String getDefaultWorkspaceRootPath() {
    return new File('.').getCanonicalPath() + "${platform.sep}workspace"
  }

  static String retrieveBaseDir() {
    return Library.getUniversalProperty('workspace.basedir', getDefaultWorkspaceRootPath())
  }

  void downloadClusterBenchJenkins(String source, String destination) {
    log.debug("Downloading clusterbench from ${source} to ${destination}")
    File destFile = new File("${ServerAbstract.getDeplSrcPath()}${File.separator}${destination}")
    if (!destFile.parentFile.exists()) {
      JBFile.mkdir(destFile.parentFile)
    }
    destFile.withOutputStream { out ->
      out << new URL(source).openStream()
    }
  }

  /**
   * Copies self-signed, pre-generated certificates from noe core to ${tmpdir}/ssl/self_signed directory.
   *
   */

  void copyCertificates() {
    List<String> certificates = ["localhost.server.cert.pem", "localhost.server.key.pem", "localhost.server.keystore.jks"]
    List<String> certificatesPaths = ["ssl/proper/generated/ca/intermediate/certs/", "ssl/proper/generated/ca/intermediate/private/", "ssl/proper/generated/ca/intermediate/keystores/"]
    String sslStringDir1 = PathHelper.join(platform.tmpDir, "ssl", "proper", "generated", "ca", "intermediate")
    String sslStringDir2 = PathHelper.join(platform.tmpDir, "ssl", "proper", "generated", "ca", "intermediate")
    String sslStringDir3 = PathHelper.join(platform.tmpDir, "ssl", "proper", "generated", "ca", "intermediate")
    File sslDir1 = new File(sslStringDir1)
    File sslDir2 = new File(sslStringDir2)
    File sslDir3 = new File(sslStringDir3)


    if (!sslDir1.exists()) {
      JBFile.mkdir(sslDir1)
    }

    if (!sslDir2.exists()) {
      JBFile.mkdir(sslDir2)
    }

    if (!sslDir3.exists()) {
      JBFile.mkdir(sslDir3)
    }

    JBFile.makeAccessible(sslDir1)
    JBFile.makeAccessible(sslDir2)
    JBFile.makeAccessible(sslDir3)

    for (int i = 0; i < 3; i++) {
      File certFile = Library.retrieveResourceAsFile("${certificatesPaths[i]}${certificates[i]}")
      JBFile.move(certFile, sslDir1)
      certFile = Library.retrieveResourceAsFile("${certificatesPaths[i]}${certificates[i]}")
      JBFile.move(certFile, sslDir2)
      certFile = Library.retrieveResourceAsFile("${certificatesPaths[i]}${certificates[i]}")
      JBFile.move(certFile, sslDir3)
    }
  }

  void downloadClusterBench() {
    if (!testAppsInstalled && !Boolean.parseBoolean(Library.getUniversalProperty("clusterbench.install.skip", "false"))) {
      def appsToDownload = [
          "${(!context.consistsOf(['eap6'])) ? DefaultProperties.CLUSTERBENCH_EE5_DIST : DefaultProperties.CLUSTERBENCH_EE6_DIST}": "${DefaultProperties.APP_PATH}",
          "${DefaultProperties.MODCLUSTER_CUSTOM_LOAD_METRIC}"                                                        : "mod_cluster${File.separator}mod_cluster-custom-metric.jar",
          "${DefaultProperties.CLUSTERBENCH_EE6_DIST_NONDIST}"                                                        : "webapps${File.separator}nondist${File.separator}clusterbench.war"
      ]
      appsToDownload.each { String source, String destination ->
        if (source.startsWith("http")) {
          downloadClusterBenchJenkins(source, destination)
        } else {
          final String ARCHIVE_DIR = "lastSuccessful${File.separator}archive"
          final String ARCHIVE_ZIP = "${ARCHIVE_DIR}.zip"
          if (platform.isWindows()) {
            //If the source doesn't contain Jenkins jobs path, nothing but slash replacement happens.
            source = source.replace(DefaultProperties.JENKINS_JOBS_DIR_PREFIX, Hudson.JOBS_DIR).replace("/", File.separator)
          }
          log.debug("Copying clusterbench from ${source} to ${destination}")

          File sourceFile = new File(source)
          if (!sourceFile.isFile() && source.startsWith(Hudson.JOBS_DIR) && source.contains(ARCHIVE_DIR)) {
            // perhaps the artifacts are archived in archive.zip, lets try to get it from the zip
            int archiveDirIdx = source.indexOf(ARCHIVE_DIR)
            String jobDir = source.substring(0, archiveDirIdx)
            String appPathArchive = source.substring(archiveDirIdx+ARCHIVE_DIR.length())
            File archiveZipFile = new File(jobDir, ARCHIVE_ZIP)
            if (archiveZipFile.isFile()) {
              log.debug("Retrieving ${appPathArchive} from archive zip: ${archiveZipFile} ")
              File unzipDestDir = new File(this.basedir, "clusterbench-app")
              JBFile.delete(unzipDestDir)
              JBFile.mkdir(unzipDestDir)
              JBFile.nativeUnzip(archiveZipFile, unzipDestDir)
              if (new File(unzipDestDir, appPathArchive).exists()) {
                sourceFile = new File(unzipDestDir, appPathArchive)
              }
            }
          }

          if (!JBFile.copyFile(sourceFile, new File("${ServerAbstract.getDeplSrcPath()}${File.separator}${destination}"))) {
            // Perhaps, mounts are not present. Let's give it a shot with Jenkins...
            if (source.startsWith(Hudson.JOBS_DIR) && source.contains(ARCHIVE_DIR)) {
              String webSource = source.replace(Hudson.JOBS_DIR, "${Library.getUniversalProperty("JENKINS_URL")}job/")
                  .replace(ARCHIVE_DIR, "lastSuccessfulBuild/artifact").replace(File.separator, "/")
              log.debug("Copying from ${source} to ${destination} failed, I'll try downloading from ${webSource}.")
              downloadClusterBenchJenkins(webSource, destination)
            } else {
              // Apparently, the source is not the default DefaultProperties.JENKINS_JOBS_DIR location, so it doesn't make sense to try to download it from Jenkins.
              throw new IOException("I cannot copy from ${source} to ${destination}. Check the resource path, please.")
            }
          }
        }
      }
      testAppsInstalled = true
    } else {
      //Silence is golden.
    }
  }

}
