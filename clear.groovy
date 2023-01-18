node {
    stage ("Clear cluster") {
        withKubeConfig([credentialsId: '', serverUrl: '', namespace: '']) {
            sh """
                kubectl delete deployment churn-model-telecom-deployment
                kubectl delete service churn-model-telecom-service
            """
        }   
    }
}