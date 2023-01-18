import groovy.yaml.*
imageName = ""
gitData = ""

node {
    stage ("Clone CI/CD repo"){
        fileOperations([folderCreateOperation("${WORKSPACE}/ci-cd")])
        dir("${WORKSPACE}/ci-cd"){
            git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: 'git@github.com:deadcomedian/NIR9-CI-CD.git', branch: "master"
        }
    }

    stage ("Clone model repo"){
        fileOperations([folderCreateOperation("${WORKSPACE}/churn_model_telecom")])
        dir("${WORKSPACE}/churn_model_telecom"){
            gitData = git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: 'git@github.com:deadcomedian/NIR9-churn-model-telecom.git', branch: "master"
        }
    }

    stage ("Build docker image & push") {
        def gitSha = gitData['GIT_COMMIT']
        def dockerTag = new Date().format("yyyyMMddHHmm")+"_" + gitSha
        sh "cp ${WORKSPACE}/ci-cd/Dockerfile ${WORKSPACE}/churn_model_telecom/Dockerfile"
        dir("${WORKSPACE}/churn_model_telecom"){
            withCredentials([usernamePassword(credentialsId: "TUZ", passwordVariable: 'password', usernameVariable: 'username')]) {
                 sh """
                    docker logout && docker login -u '${username}' -p '${password}'
                    docker build --tag deadcomedian/churn_model_telecom:${dockerTag} .
                    docker push deadcomedian/churn_model_telecom:${dockerTag}
                """
            }
        }

        stage ("Edit Kubernetes deployment manifest"){
            def deployment = readYaml file: "${WORKSPACE}/ci-cd/deployment.yaml"
            deployment.spec.template.spec.containers[0].image = "deadcomedian/churn_model_telecom:${dockerTag}"
            sh "rm ${WORKSPACE}/ci-cd/deployment.yaml"
            writeYaml file: "${WORKSPACE}/ci-cd/deployment.yaml", data: deployment
            dir("${WORKSPACE}/ci-cd"){
                withCredentials([sshUserPrivateKey(credentialsId: "TUZ_ssh", keyFileVariable: 'id_rsa')]) {
                    sh """
                        git config --global user.name "Mr.Jenkins"
                        git config --global user.email "bozheboy@yandex.ru"
                        git checkout master
                        git add *
                        git commit -am 'Deployment patched with new docker image ${dockerTag}'
                        GIT_SSH_COMMAND='ssh -i $id_rsa' git push --set-upstream origin master
                    """
                }
            }
        }


        currentBuild.description = "Название образа: deadcomedian/churn_model_telecom:${dockerTag}"
    }
}
