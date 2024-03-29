import groovy.yaml.*

replicasNumber = 0
modelImageName = ""

properties([
  parameters([
    string(name: 'modelName', description: 'Model name')
  ]),
  pipelineTriggers([
    parameterizedCron('''
        0 11 * * * %modelName=churn_model_telecom
        0 12 * * * %modelName=churn_model_test
    ''')
  ])
])

node {
    stage ("Clone CI/CD repo and configure job") {
        fileOperations([folderCreateOperation("${WORKSPACE}/ci-cd")])
        dir("${WORKSPACE}/ci-cd"){
            git changelog: false, poll: false, credentialsId: 'TUZ_ssh', url: 'git@github.com:deadcomedian/NIR9-CI-CD.git', branch: "master"
        }
        def modelConfig = readYaml file: "${WORKSPACE}/ci-cd/model_configs/${modelName}.yml"
        modelImageName = modelConfig.model_image_name
    }

    stage("Edit manifests"){
        def deployment = readYaml file: "${WORKSPACE}/ci-cd/deployment.yaml"
        deployment.metadata.name = "${modelName.replaceAll("_", "-")}-deployment"
        deployment.spec.selector.matchLabels.app = modelName
        deployment.spec.template.metadata.labels.app = modelName
        deployment.spec.template.spec.containers[0].image = modelImageName
        replicasNumber = deployment.spec.replicas
        sh "rm ${WORKSPACE}/ci-cd/deployment.yaml"
        writeYaml file: "${WORKSPACE}/ci-cd/deployment.yaml", data: deployment

        def service = readYaml file: "${WORKSPACE}/ci-cd/service.yaml"
        service.metadata.name = "${modelName.replaceAll("_", "-")}-service"
        service.spec.selector.app = modelName
        sh "rm ${WORKSPACE}/ci-cd/service.yaml"
        writeYaml file: "${WORKSPACE}/ci-cd/service.yaml", data: service

        def ingress = readYaml file: "${WORKSPACE}/ci-cd/ingress.yaml"
        ingress.metadata.name = "${modelName.replaceAll("_", "-")}-ingress"
        ingress.spec.rules[0].http.paths[0].backend.service.name = "${modelName.replaceAll("_", "-")}-ingress"
        sh "rm ${WORKSPACE}/ci-cd/ingress.yaml"
        writeYaml file: "${WORKSPACE}/ci-cd/ingress.yaml", data: ingress
    }

    stage("Deploy model in Kubernetest cluster") {
        dir("${WORKSPACE}/ci-cd"){
            withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG')]) {
                //sh "kubectl config set-context $(kubectl config current-context)"
                sh "echo $KUBECONFIG"
                sh """
                    kubectl apply -f deployment.yaml
                    kubectl apply -f service.yaml
                    kubectl apply -f ingress.yaml
                """
            }
        }
    }

    sleep 300

    stage("Check readiness") {
        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG')]) {
            def output = sh (script: "kubectl get deployment ${modelName.replaceAll("_", "-")}-deployment", returnStdout: true) 
            if (output.contains("${replicasNumber}/${replicasNumber}")) {
                currentBuild.displayName = "${modelName} Deployed 🏖 ☀️ 🌴"
                currentBuild.result = "SUCCESS"
            } else {
                currentBuild.displayName = "${modelName} Not Deployed 💀"
                currentBuild.result = "FAILURE"
            }
        }
    }
}
