import groovy.yaml.*
import groovy.json.*
gitData = ""

modelConfig = [:]
modelGitRepo = ""
modelDockerRepo = ""
modelImageName = ""
sonarProject = ""

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
        CI_FLAG = 'err'
        error("Код не прошёл проверку")
    }
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
    }

    stage ("Clone model repo"){
        fileOperations([folderCreateOperation("${WORKSPACE}/${modelName}")])
        dir("${WORKSPACE}/${modelName}"){
            gitData = git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: modelGitRepo, branch: "master"
        }
    }

    /*
    stage("SonarQube check"){
        def scannerHome = tool 'SonarQube'
        def shOutput = ""
        dir("${WORKSPACE}/${modelName}"){
            withSonarQubeEnv(credentialsId: "sonarToken", installationName: 'SonarQube') {
                sh "${scannerHome}/bin/sonar-scanner -X -Dsonar.projectKey=${sonarProject}"
            }
            shOutput= sh(script: "cat .scannerwork/report-task.txt", returnStdout: true)
        }
        //вырезаем из файла значение taskId
        shOutput = shOutput.split("ceTaskId=")[1]
        //def taskId = shOutput.split("ceTaskUrl=")[0]

        def taskUrl = shOutput.split("ceTaskUrl=")[1]

        sleep 180
        
        //получаем analysisId
        def analysisId = getAnalysisIdByTaskId(taskUrl)
        
        checkQualityGatesStatusAndFailIfNotOK(analysisId)
    }
    */
    
    stage("OWASP Dependency Check"){
        dependencyCheck additionalArguments: ''' 
                    -o "./" 
                    -s "./"
                    -f "ALL" 
                    --prettyPrint''', odcInstallation: 'Dependency Check'

        dependencyCheckPublisher pattern: 'dependency-check-report.xml'
    }

    stage ("Build docker image & push") {
        def gitSha = gitData['GIT_COMMIT']
        def dockerTag = new Date().format("yyyyMMddHHmm")+"_" + gitSha
        sh "cp ${WORKSPACE}/ci-cd/Dockerfile ${WORKSPACE}/${modelName}/Dockerfile"
        dir("${WORKSPACE}/${modelName}"){
            withCredentials([usernamePassword(credentialsId: "TUZ", passwordVariable: 'password', usernameVariable: 'username')]) {
                modelImageName = "${modelDockerRepo}:${dockerTag}"
                 sh """
                    docker logout && docker login -u '${username}' -p '${password}'
                    docker build --tag ${modelImageName} .
                    docker push ${modelImageName}
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
    }
}
