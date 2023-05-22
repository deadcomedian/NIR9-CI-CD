import groovy.yaml.*
import groovy.json.*
    
try{
    if (merge_commit_sha == "null"){
        currentBuild.result = 'ABORTED'
    }
} catch (Exception e){
    println("STRAIGHT JENKINS BUILD NO SCM EVENTS")
}

    
gitData = ""

modelConfig = [:]
modelGitRepo = ""
modelDockerRepo = ""
modelImageName = ""
sonarProject = ""
zapTarget = ""
analysisId = ""

def getAnalysisIdByTaskId(String taskUrl){
    //делаем запрос
    def response = httpRequest acceptType: 'APPLICATION_JSON', authentication: "TUZ", url: taskUrl
    def json = new JsonSlurper().parseText(response.content)
    def analysisId = json.task.analysisId
    return analysisId
}

def checkQualityGatesStatusAndFailIfNotOK(String analysisId){
    //собираем url для запроса на получение статуса проверки QG
    def requestUrlForQGStatus = "http://94.156.189.88:9000/api/qualitygates/project_status?analysisId=" + analysisId
    //делаем запрос
    def response = httpRequest acceptType: 'APPLICATION_JSON', authentication: "TUZ", url: requestUrlForQGStatus
    def json = new JsonSlurper().parseText(response.content)
    def qgStatus = json.projectStatus.status
    //проверяем статус
    if (!qgStatus.equals("OK")){
        //если не OK, то фейлим
        currentBuild.result = 'FAILED'
        error("Код не прошёл проверку SonarQube")
    }
}

def getZAPHighLevelVulnerabilitiesCount(pathToZAPReport){
    def report = readFile file: pathToZAPReport
    def parts = report.split("\n")
    def highLevelVulnurabilitiesCount
    for(int i = 0; i<parts.size() ; i++){
        parts[i] = parts[i].trim()
        if(parts[i]!='<td class="risk-3">'){
            continue;
        }
        def hlvString=parts[i+4]
        highLevelVulnurabilitiesCount = hlvString.replaceAll("<div>", "").replaceAll("</div>", "").toInteger()
        break
    }
    return highLevelVulnurabilitiesCount
}

def getODCVulnerabilitiesCount(pathToODCReport){
    def report = readFile file: pathToODCReport
    def parts = report.split("Vulnerabilities Found</i>:&nbsp;")
    vulnerabilitiesCount = parts[1].split("</li><li><i>Vulnerabilities Suppressed")[0].toInteger()
    return vulnerabilitiesCount
}

node {

    stage ("Clone CI/CD repo and configure job"){
        fileOperations([folderCreateOperation("${WORKSPACE}/ci-cd")])
        dir("${WORKSPACE}/ci-cd"){
            git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: 'git@github.com:deadcomedian/NIR9-CI-CD.git', branch: "master"
        }
        modelConfig = readYaml file: "${WORKSPACE}/ci-cd/model_configs/${modelName}.yml"
        modelGitRepo = modelConfig.git_repo
        modelDockerRepo = modelConfig.docker_repo
        sonarProject = modelConfig.sonar_project
        zapTarget = modelConfig.endpoint
    }

    stage ("Clone model repo"){
        fileOperations([folderCreateOperation("${WORKSPACE}/${modelName}")])
        dir("${WORKSPACE}/${modelName}"){
            gitData = git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: modelGitRepo, branch: "master"
        }
    }
    
    stage("SonarQube check"){
        def scannerHome = tool 'SonarQube'
        def shOutput = ""
        dir("${WORKSPACE}/${modelName}"){
            withSonarQubeEnv(credentialsId: "sonarToken", installationName: 'SonarQube') {
                sh "${scannerHome}/bin/sonar-scanner -X -Dsonar.projectKey=${sonarProject}"
            }
            shOutput= sh(script: "cat .scannerwork/report-task.txt", returnStdout: true)
        }

        shOutput = shOutput.split("ceTaskId=")[1]
        
        def taskUrl = shOutput.split("ceTaskUrl=")[1]

        sleep 180
        
        analysisId = getAnalysisIdByTaskId(taskUrl)
        
    }
    
    stage("OWASP Dependency Check"){
        dir("${WORKSPACE}/${modelName}"){
            dependencyCheck additionalArguments: 
            '''--out "." 
            --scan "." 
            --format "ALL" 
            --prettyPrint 
            --exclude ".git/**"
            --enableExperimental
            ''', odcInstallation: 'Dependency-Check'
            dependencyCheckPublisher pattern: 'dependency-check-report.*', stopBuild: true
            sh "ls -halt"
            sh "cat dependency-check-report.json"
            archiveArtifacts "dependency-check-report.html"
        }
    }

    stage ("Build docker image") {
        def gitSha = gitData['GIT_COMMIT']
        def dockerTag = new Date().format("yyyyMMddHHmm")+"_" + gitSha
        sh "cp ${WORKSPACE}/ci-cd/Dockerfile ${WORKSPACE}/${modelName}/Dockerfile"
        dir("${WORKSPACE}/${modelName}"){
            modelImageName = "${modelDockerRepo}:${dockerTag}"
            sh "docker build --tag ${modelImageName} ."
        }
    }

    stage("OWASP ZAP Check"){
        fileOperations([folderCreateOperation("${WORKSPACE}/zap-scans")])
        try{
            sh """
                docker network create zapnet
                docker run -d --rm --name ${modelName} --net zapnet ${modelImageName}
                docker run -dt --rm --name owasp --net zapnet owasp/zap2docker-stable /bin/bash
                docker exec owasp mkdir /zap/wrk 
                docker exec owasp zap-full-scan.py -t http://${modelName}${zapTarget} -r report.html -I
                docker cp owasp:/zap/wrk/report.html ${WORKSPACE}/zap-scans

                ls -halt ${WORKSPACE}/zap-scans
            """
            archiveArtifacts "zap-scans/report.html"
        } finally {
            sh """
                docker stop ${modelName}
                docker stop owasp
                docker network rm zapnet
            """
        }
        
    }

    stage("Process analysis results"){

        checkQualityGatesStatusAndFailIfNotOK(analysisId)

        if (getODCVulnerabilitiesCount("${WORKSPACE}/${modelName}/dependency-check-report.html") > 0){
            currentBuild.result = 'FAILED'
            error("Код не прошёл проверку ODC")
        }

        if (getZAPHighLevelVulnerabilitiesCount("${WORKSPACE}/zap-scans/report.html")>0) {
            currentBuild.result = 'FAILED'
            error("Код не прошёл проверку ZAP")
        }
    }

    stage("Push docker image"){
        withCredentials([usernamePassword(credentialsId: "TUZ", passwordVariable: 'password', usernameVariable: 'username')]) {
            sh """
                docker logout && docker login -u '${username}' -p '${password}'
                docker push ${modelImageName}
                docker rmi ${modelImageName}
            """
        } 
    }

    stage("Edit model_config/${modelName}.yml with new image"){
        modelConfig.model_image_name = modelImageName
        sh "rm ${WORKSPACE}/ci-cd/model_configs/${modelName}.yml"
        writeYaml file: "${WORKSPACE}/ci-cd/model_configs/${modelName}.yml", data: modelConfig
        dir("${WORKSPACE}/ci-cd"){
            withCredentials([sshUserPrivateKey(credentialsId: "TUZ_ssh", keyFileVariable: 'id_rsa')]) {
                sh """
                    git config --global user.name "Mr.Jenkins"
                    git config --global user.email "bozheboy@yandex.ru"
                    git checkout master
                    git add *
                    git commit -am '${modelName} config pathced with new image name ${modelImageName}'
                    GIT_SSH_COMMAND='ssh -i $id_rsa' git push --set-upstream origin master
                """
            }
        }
    }
    currentBuild.description = "Название образа: ${modelImageName}"
    currentBuild.displayName = "Собрали ${modelName}"
    cleanWs()
}