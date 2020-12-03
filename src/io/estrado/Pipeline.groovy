#!/usr/bin/groovy
package io.estrado;

def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"

}

def helmLint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"

}

def helmPackage(String chart_dir) {
    // package helm chart
    println "running helm package ${chart_dir}"
    sh "helm package ${chart_dir}"

}

def helmConfig() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initiliazing helm client"
    // sh "helm init --client-only"
    sh 'HELM_VERSION=$(helm version)'
    sh 'if [[ "${HELM_VERSION}" == 2* ]]; then helm init --client-only; else echo "using helm3, no need to initialize helm"; fi'
    println "checking client/server version"
    sh "helm version"
}


def helmDeploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmConfig()
    def String release_overrides = ""
    if (args.set) {
      release_overrides = getHelmReleaseOverrides(args.set)
    }

    def String namespace

    // If namespace isn't parsed into the function set the namespace to the name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} " + (release_overrides ? "--set ${release_overrides}" : "") + " --namespace=${namespace}"
    } else {
        println "Running deployment"

        sh "helm dependency update ${args.chart_dir}"
        
        sh "helm upgrade --install ${args.name} ${args.chart_dir} " + (release_overrides ? "--set ${release_overrides}" : "") + " --namespace=${namespace}" + " --wait"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
        println "Running helm delete ${args.name}"

        sh "helm delete ${args.name}"
}

def helmTest(Map args) {
    println "Running Helm test"

    sh "helm test ${args.name} --cleanup"
}

def gitEnvVars() {
    // create git envvars
    println "Setting envvars to tag container"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"

    sh 'git config --get remote.origin.url> git_remote_origin_url.txt'
    try {
        env.GIT_REMOTE_URL = readFile('git_remote_origin_url.txt').trim()
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_REMOTE_URL ==> ${env.GIT_REMOTE_URL}"
}

def containerBuild(Map args) {

    println "Running Docker build: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    docker.withRegistry("https://${args.host}", "${args.auth_id}") {

        // def img = docker.build("${args.acct}/${args.repo}", args.dockerfile)
        def img = docker.image("${args.acct}/${args.repo}")
        sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.acct}/${args.repo} ${args.dockerfile}"

        env.IMAGE_ID = "${args.host}/${args.acct}/${args.repo}:${args.buildTag}"

        println "env.IMAGE_ID  ${env.IMAGE_ID}"
    }
}

def containerPublish(Map args) {

    println "Running Docker publish: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    docker.withRegistry("https://${args.host}", "${args.auth_id}") {

        // def img = docker.build("${args.acct}/${args.repo}", args.dockerfile)
        def img = docker.image("${args.acct}/${args.repo}")
        // sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.acct}/${args.repo} ${args.dockerfile}"
        for (int i = 0; i < args.tags.size(); i++) {
            img.push(args.tags.get(i))
        }

        return img.id
    }
}

def azLogin(Map args) {
    println "Logging into Azure CLI with provided service principal..."

    sh "az login --service-principal -u ${args.appid} -p ${env.PASSWORD} --tenant ${env.TENANT_ID}"
}

def azHelmUpload(Map args) {
    println "Uploading helm chart to ACR"

    sh "az acr helm push -n ${args.repo} *.tgz --force"
}

def githubConfidence(Map args) {
    println "Adding a dash of confidence to your process..."
    
    // env.COMMENT_PREFIX = "You can see a private version of the changes made in this  pull request  here - http://"

    // commenter.withRun("-e ${env.GITHUB_TOKEN} -e ${args.GITHUB_OWNER} -e ${args.GITHUB_REPO} -e ${args.GITHUB_COMMENT_TYPE} -e ${args.GITHUB_PR_ISSUE_NUMBER}")
    // sh "-t ${env.GITHUB_TOKEN}"

    docker.withRun("-t ${env.GITHUB_TOKEN} -owner ${args.GITHUB_OWNER} -repo ${args.GITHUB_REPO} -type ${args.GITHUB_COMMENT_TYPE} -number ${args.GITHUB.PR.ISSUE.Number} -comment 'You can see a private version of the changes made in this  pull request  here - http://${env.PREVIEW_URL}'")

    // ('-p 3000:3000', '--arg1 somearg --arg2 anotherarg')
    // sh "docker run -i --rm -e ${env.GITHUB_TOKEN} -e ${args.GITHUB_OWNER} -e ${args.GITHUB_REPO} -e ${args.GITHUB_COMMENT_TYPE} -e ${args.GITHUB_PR_ISSUE_NUMBER} -e ${GITHUB_COMMENT}"
}

def getContainerTags(config, Map tags = [:]) {

    println "getting list of tags for container"
    def String commit_tag
    def String version_tag

    try {
        // if PR branch tag with only branch name
        if (env.BRANCH_NAME.contains('PR')) {
            commit_tag = env.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // commit tag
    try {
        // if branch available, use as prefix, otherwise only commit hash
        if (env.BRANCH_NAME) {
            commit_tag = env.BRANCH_NAME + '-' + env.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = env.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // master tag
    try {
        if (env.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: branch unavailable from env. ${e}"
    }

    // build tag only if none of the above are available
    if (!tags) {
        try {
            tags << ['build': env.BUILD_TAG]
        } catch (Exception e) {
            println "WARNING: build tag unavailable from config.project. ${e}"
        }
    }

    return tags
}

def getContainerRepoAcct(config) {

    println "setting container registry creds according to Jenkinsfile.json"
    def String acct

    if (env.BRANCH_NAME == 'master') {
        acct = config.container_repo.master_acct
    } else {
        acct = config.container_repo.alt_acct
    }

    return acct
}

@NonCPS
def getMapValues(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
}

@NonCPS
def getHelmReleaseOverrides(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.each(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def options = ""
    map.each { key, value ->
        options += "$key=$value,"
    }

    return options
}

def String getDomainName(String url) throws URISyntaxException {
    URI uri = new URI(url);
    String domain = uri.getHost();
    return domain.startsWith("www.") ? domain.substring(4) : domain;
}

def String getSubDomainName(String domain) {
    return domain.substring(domain.indexOf('.') + 1);
}

// Used to get the subdomain Jenkins is hosted on for new ingress resources.
def String getSubDomainNameFromURL(String url) {
    return getSubDomainName(getDomainName(url));
}
