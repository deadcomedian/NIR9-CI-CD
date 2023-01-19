import groovy.yaml.*
replicasNumber = 0

node {
    stage ("Clone CI/CD repo") {
        fileOperations([folderCreateOperation("${WORKSPACE}/ci-cd")])
        dir("${WORKSPACE}/ci-cd"){
            git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: 'git@github.com:deadcomedian/NIR9-CI-CD.git', branch: "master"
        }
    }

    stage("Deploy model in Kubernetest cluster") {
        def deployment = readYaml file: "${WORKSPACE}/ci-cd/deployment.yaml"
        replicasNumber = deployment.spec.replicas
        dir("${WORKSPACE}/ci-cd"){
            withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'FILE')]) {
                sh """
                    cat $FILE >> ${WORKSPACE}/kubeconfig.yaml
                    cat ${WORKSPACE}/kubeconfig.yaml
                    export KUBEONFIG=${WORKSPACE}/kubeconfig.yaml
                    kubectl apply -f deployment.yaml
                    kubectl apply -f service.yaml
                """
            }
        }
    }

    sleep 300

    stage("Check readiness") {
        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'FILE')]) {
            sh "export KUBEONFIG=$FILE"
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