import groovy.yaml.*
gitData = ""

modelConfig = [:]
modelGitRepo = ""
modelDockerRepo = ""
modelImageName = ""
sonarProject = ""


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

    stage("SonarQube check"){
        def scannerHome = tool 'SonarScanner'
        dir("${WORKSPACE}/${modelName}"){
            withSonarQubeEnv(credentialsId: sonarToken, installationName: 'SonarQube') {
                sh "${scannerHome}/bin/sonar-scanner -X -Dsonar.projectKey=${sonarProject}"
            }
        }
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

        stage("Edit model_config/{modelName}.yml with new image"){
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
