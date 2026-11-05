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
                        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
                        export PATH=$JAVA_HOME/bin:$PATH
                        javac --version
                        mvn --version
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
