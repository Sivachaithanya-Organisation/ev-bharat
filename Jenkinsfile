pipeline {
    agent any

    environment {
        SERVER_IP = credentials('maven-server-ip')
        APP_NAME = "ev-bharat"
    }

    stages {
        
        stage('checkout'){
            steps {
                checkout scm
            }
        }

        stage('maven build and deploy'){
            steps {
              sshagent (credentials: ['maven-server']) {
                sh '''
                    ssh -o StrictHostKeyChecking=no ubuntu@${SERVER_IP} "
                        rm -rf ${APP_NAME}
                        git clone https://github.com/Sivachaithanya-Organisation/ev-bharat.git
                        cd ${APP_NAME}
                        mvn clean package
                        sudo cp target/${APP_NAME}.war /opt/tomcat/webapps/
                    "
                '''
              }
            }
        }
    }
}
