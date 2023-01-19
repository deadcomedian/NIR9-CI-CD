node {
    stage ("Clear cluster") {
        withCredentials([file(credentialsId: 'KUBECONFIG', variable: 'KUBECONFIG')]) {
            sh """
                kubectl delete deployment churn-model-telecom-deployment
                kubectl delete service churn-model-telecom-service
            """
        }   
    }
}