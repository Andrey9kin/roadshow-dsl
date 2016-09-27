import jenkins.*
import jenkins.model.*

/*
 * Use this function to enable must have-defaults for your jobs
 * LogRotator - make sure that you are cleaning up old logs
 * Timeout - kill job in case if it takes to long
 *           This will indicate a coming problem for you and unclock
 *           pipeline in case of hanging jobs
 * Timestamps - just nice to have have feature. Adds time stamps
 *              to console output
 *
 */
def addDefaultParameters(def context, buildsToKeep=50, artifactsToKeep=10, timeoutVal=40) {
    // Add timestamps and timeouts
    context.wrappers {
        timestamps()
        timeout {
            absolute(timeoutVal)
        }
    }
    // Set log rotator
    context.logRotator {
        numToKeep(buildsToKeep)
        artifactNumToKeep(artifactsToKeep)
    }
}

/*
 * Use this function to setup git clone to your repo
 * repoURL - URL for repository to clone
 * branch - branch name to checkout
 *
 */
def addGitSCM(def context, repoURL, branchName='master', credentialsId='jenkins') {
    context.scm {
        git{
            remote {
                name('origin')
                url(repoURL)
                credentials(credentialsId)
            }
            branch(branchName)
            extensions {
                cleanBeforeCheckout()
            }
        }
    }
}

/*
 * Create a view
 */
listView("${GITHUB_USER}") {
    description('All jobs for GitHub user ${GITHUB_USER}')
    jobs {
        regex(/${GITHUB_USER}.+/)
    }
    columns {
        name()
        status()
        weather()
        lastDuration()
        buildButton()
    }
}

/*
 * Verify that we can build war, run unit tests and measure code coverage
 */
job("${GITHUB_USER}.roadshow.generated.build") {
    // Set default parameters
    addDefaultParameters(delegate)
    // Add Git SCM
    addGitSCM(delegate, "git@github.com:${GITHUB_USER}/roadshow.git")
    // Set trigger to poll SCM every minute
    triggers {
        scm('* * * * *')
    }
    // Actual build steps
    steps {
        // Build war file, run tests and measure coverage
        shell('./gradlew clean war jenkinstest jacoco')
        // Just for fun
        shell("echo 'Hello, world!!'")
    }
    // Post build steps
    publishers {
        // Collect code coverage report
        jacocoCodeCoverage()
        // Collect unit test results
        archiveJunit('build/test-results/*.xml')
        // Collect compilation warnings
        warnings(['Java Compiler (javac)'])
        // Trigger downstream job
        downstream("${GITHUB_USER}.roadshow.generated.staticanalysis",
                   'SUCCESS')
    }
}

/*
 * Run static analysis and post results
 */
job("${GITHUB_USER}.roadshow.generated.staticanalysis") {
    // Set default parameters
    addDefaultParameters(delegate)
    // Add Git SCM
    addGitSCM(delegate, "git@github.com:${GITHUB_USER}/roadshow.git")
    // Actual build steps
    steps {
        // Run static code analysis
        shell('./gradlew staticanalysis')
    }
    // Post-build steps
    publishers {
        // Collect check style report
        checkstyle('build/reports/checkstyle/*.xml')
        // Collect PMD report
        pmd('build/reports/pmd/*.xml')
        // Collect tasks statistics
        tasks('**/*', '', 'FIXME', 'TODO', 'LOW', true)
    }
}


/*
 * BuilFlow driven pipeline
 */

buildFlowJob("${GITHUB_USER}.roadshow.buildflow") {
  parameters {
    stringParam('GITHUB_USER', "${GITHUB_USER}")
  }
    buildFlow('''
GITHUB_USER = params["GITHUB_USER"]

buildArtefact = build(GITHUB_USER + ".roadshow.buildflow.build")
parallel (
    { build(GITHUB_USER + ".roadshow.buildflow.test") },
    { build(GITHUB_USER + ".roadshow.buildflow.metrics") }
)
build(GITHUB_USER + ".roadshow.buildflow.promote", "BUILD_JOB_NAME": GITHUB_USER + ".roadshow.buildflow.build", "BUILD_JOB_NUMBER": buildArtefact.build.number)
''')
}

job("${GITHUB_USER}.roadshow.buildflow.build") {
    // Set default parameters
    addDefaultParameters(delegate)
    // Add Git SCM
    addGitSCM(delegate, "git@github.com:${GITHUB_USER}/roadshow.git")
    // Actual build steps
    steps {
        // Build war file, run tests and measure coverage
        shell('./gradlew war -DbuildNumber=${GITHUB_USER}-${BUILD_NUMBER}')
    }
    // Copy artifact permission for promotion job
    configure {
        it / 'properties' << 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
            'projectNameList' {
    	        'string' "${GITHUB_USER}.roadshow.buildflow.promote"
            }
        }
    }
    // Post build steps
    publishers {
        archiveArtifacts {
            pattern('build/libs/RoadShow-*.war')
            onlyIfSuccessful()
        }
    }
}

job("${GITHUB_USER}.roadshow.buildflow.test") {
    // Set default parameters
    addDefaultParameters(delegate)
    // Add Git SCM
    addGitSCM(delegate, "git@github.com:${GITHUB_USER}/roadshow.git")
    // Actual build steps
    steps {
        // Build war file, run tests and measure coverage
        shell('./gradlew test')
    }
    publishers {
        // Collect unit test results
        archiveJunit('build/test-results/*.xml')
    }
}

job("${GITHUB_USER}.roadshow.buildflow.metrics") {
    // Set default parameters
    addDefaultParameters(delegate)
    // Add Git SCM
    addGitSCM(delegate, "git@github.com:${GITHUB_USER}/roadshow.git")
    // Actual build steps
    steps {
        // Build war file, run tests and measure coverage
        shell('./gradlew check')
    }
    // Post build steps
    publishers {
        // Collect check style report
        checkstyle('build/reports/checkstyle/*.xml')
        // Collect PMD report
        pmd('build/reports/pmd/*.xml')
        // Collect tasks statistics
        tasks('**/*', '', 'FIXME', 'TODO', 'LOW', true)
        // Collect code coverage report
        jacocoCodeCoverage()
        // Collect unit test results
        archiveJunit('build/test-results/*.xml')
        // Collect compilation warnings
        warnings(['Java Compiler (javac)'])
    }
}

job("${GITHUB_USER}.roadshow.buildflow.promote") {
    // Set default parameters
    addDefaultParameters(delegate)
    parameters {
      stringParam('BUILD_JOB_NAME', '')
      stringParam('BUILD_JOB_NUMBER', '')
    }
    // Actual build steps
    steps {
        copyArtifacts('$BUILD_JOB_NAME') {
            includePatterns('build/libs/*.war')
            targetDirectory('.')
            flatten()
            buildSelector {
                buildNumber('$BUILD_JOB_NUMBER')
                latestSuccessful(true)
            }
    }
    }
  configure { project ->
    project / buildWrappers << 'org.jfrog.hudson.generic.ArtifactoryGenericConfigurator' {
      deployPattern('*.war')
      details {
            artifactoryName('local-server')
            artifactoryUrl('http://artifactory:8080/artifactory')
            deployReleaseRepository {
                keyFromSelect('libs-snapshot-local')
                dynamicMode(false)
            }
      }
    }
  }
}
