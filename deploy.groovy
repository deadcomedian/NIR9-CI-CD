import groovy.yaml.*
replicasNumber = 0

node {
    stage ("Clone CI/CD repo") {
        fileOperations([folderCreateOperation("${WORKSPACE}/ci-cd")])
        dir("${WORKSPACE}/ci-cd"){
            git changelog: false, poll: false, credentialsId: '', url: '', branch: "master"
        }
    }

    stage("Deploy model in Kubernetest cluster") {
        def deployment = readYaml file: "${WORKSPACE}/ci-cd/deployment.yaml"
        replicasNumber = deployment.spec.replicas
        dir("${WORKSPACE}/ci-cd"){
            withKubeConfig([credentialsId: '', serverUrl: '', namespace: '']) {
                sh """
                    kubectl apply -f deployment.yaml
                    kubectl apply -f service.yaml
                """
            }   
        }
    }

    sleep 300

    stage("Check readiness") {
        withKubeConfig([credentialsId: '', serverUrl: '', namespace: '']) {
            withKubeConfig([credentialsId: '', serverUrl: '', namespace: '']) {
                def output = sh (script: "kubectl get deployment churn-model-telecom-deployment", returnStdout: true) 
                if (output.contains("${replicasNumber}/${replicasNumber}")) {
                    currentBuild.displayName = "Deployed 🏖 ☀️ 🌴"
                    currentBuild.result = "SUCCESS"
                } else {
                    currentBuild.displayName = "Not Deployed 💀"
                    currentBuild.result = "FAILURE"
                }
            }
        }
    }
}