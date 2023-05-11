properties([
  parameters([
    string(name: 'modelName', description: 'Model name')
  ]),
  pipelineTriggers([
    parameterizedCron('''
        0 13 * * * %modelName=churn_model_telecom
    ''')
  ])
])

node {
    stage ("Clear cluster") {
        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG')]) {
            sh """
                kubectl delete deployment ${modelName.replaceAll("_", "-")}-deployment
                kubectl delete service ${modelName.replaceAll("_", "-")}-service
                kubectl delete ingress ${modelName.replaceAll("_", "-")}-ingress
            """
        }   
    }
}
