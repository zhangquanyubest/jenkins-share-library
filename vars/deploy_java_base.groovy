#!groovy

import org.devops.otherTools

def call(Map map) {
    pipeline {
        agent {
            label map.RUN_NODE
        }
        environment {
            SERVICE_NAME = "${map.SERVICE_NAME}" // 需要修改此处，定义部署到远程的项目名称
            GIT_URL = "${map.GIT_URL}"// 主项目地址
            HOSTS="${map.HOSTS}"   // 定义要部署的主机列表，多台用 \n 分隔
            BUILD_COMMAND="${map.BUILD_COMMAND}" // 定义项目编译命令
            FREE_COMMAND="${map.FREE_COMMAND}" // 定义项目部署之后执行的脚本
            EXCLUDE_FILE="${map.EXCLUDE_FILE}" // 定义忽略文件或目录，多个用 \n 分割
            BUILD_BASE_IMAGE="${map.BUILD_BASE_IMAGE}" // 用于打包编译的基础镜像
            PROJECT_FILE_PATH="${map.PROJECT_FILE_PATH}" // 指定将要部署到远程的目录
            //ROBOT_KEY = "${map.ROBOT_KEY}"  // 企业微信机器人key
            VERSION_KEY="${map.VERSION_KEY}" // 指定版本文件的key
            VERSION_FILE="${map.VERSION_FILE}" // 指定版本文件的路径
            WEBROOT_DIR="${map.WEBROOT_DIR}" // 定义项目的webroot目录
            //NOTICE_MEMBER="${map.NOTICE_MEMBER}" // 定义钉钉通知人员ID

            GITLAB_AUTH_TOKEN="auth-gitlab" // 与gitlab认证的token，不需要更改
            // adminapitoken 预留对接gitlab中webhook的token，在jenkins的admin账号设置API Token生成，在jenkins全局变量里面配置了
            // 定义项目的临时压缩目录，一般不需要更改
            BUILD_TMP="/data/build"
            // 定义ansible-base目录
            ANSIBLE_BASE="${WORKSPACE}/ansible_tmp/deployjavabase"
            // 定义构建镜像执行的参数
            BUILD_ARGS="-u 0:0 -v /data/jenkins/maven/settings.xml:/usr/share/maven/conf/settings.xml -v /etc/hosts:/etc/hosts"
            // 定义主机hosts文件，一般不用更改
            ANSIBLE_HOSTS="${ANSIBLE_BASE}/deploy_hosts/${env.JOB_BASE_NAME}_hosts"
            // ansible 剧本地址，一般不用更改
            GIT_URL_ANSIBLE = "https://gitlab.zsnetwork.com/ywbzb/yth2/deploy-playbook.git"
        }
        options {
            timestamps()
            disableConcurrentBuilds()
            timeout(time: 10, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '12'))
        }
        // gitlab webhook已经测通，需要修改过滤的分支
        //triggers{
        //    gitlab(triggerOnPush: true, triggerOnMergeRequest: true, branchFilterType: 'All', secretToken: "${env.adminapitoken}") // 预留Gitlab提交自动构建
        //}
        parameters {
            string(name: 'BRANCH', defaultValue: map.DEFAULT_BRANCH, description: '请输入将要构建的代码分支')
            choice(name: 'REMOTE_HOST', choices: map.HOSTS, description: '选择要发布的主机,默认为ALL') // 定义项目对应的主机列表
            choice(name: 'MODE', choices: ['DEPLOY','ROLLBACK'], description: '请选择发布或者回滚？')
            extendedChoice(description: '回滚版本选择,倒序排序，只保留最近十次版本；如果选择发布则忽略此项', multiSelectDelimiter: ',', name: 'ROLLBACK_VERSION', propertyFile: map.VERSION_FILE, propertyKey: map.VERSION_KEY, quoteValue: false, saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', visibleItemCount: 10)
        }
        stages {
            stage('拉取代码') {
                when {
                    environment name: 'MODE',value: 'DEPLOY'
                }
                steps {
                    script {
                        try {
                            checkout(
                                [$class: 'GitSCM', doGenerateSubmoduleConfigurations: false, submoduleCfg: [], extensions: [[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]],
                                branches: [[name: "$BRANCH"]],userRemoteConfigs: [[url: "${env.GIT_URL}", credentialsId: "${env.GITLAB_AUTH_TOKEN}"]]]
                            )
                            // 定义全局变量
                            env.PULL_TIME = sh(script: "echo `date +'%Y-%m-%d %H:%M:%S'`", returnStdout: true).trim() // 获取时间
                            env.COMMIT_ID   = sh(script: 'git log --pretty=format:%h',  returnStdout: true).trim() // 提交ID
                            env.TRACE_ID = sh(script: "echo `date +'%s%N' | base64`",  returnStdout: true).trim() // 随机生成TRACE_ID
                            env.COMMIT_USER = sh(script: 'git log --pretty=format:%an', returnStdout: true).trim() // 提交者
                            env.COMMIT_TIME = sh(script: 'git log --pretty=format:%ai', returnStdout: true).trim() // 提交时间
                            env.COMMIT_INFO = sh(script: 'git log --pretty=format:%s',  returnStdout: true).trim() // 提交信息
                            env._VERSION = sh(script: "echo `date '+%Y%m%d%H%M%S'`" + "_${COMMIT_ID}" + "_${env.BUILD_ID}", returnStdout: true).trim() // 对应构建的版本 时间+commitID+buildID
                        }catch(exc) {
                            // 添加变量占位，以避免构建异常
                            env.PULL_TIME   = "无法获取"
                            env.COMMIT_ID   = "无法获取"
                            env.TRACE_ID = "无法获取"
                            env.COMMIT_USER = "无法获取"
                            env.COMMIT_TIME = "无法获取"
                            env.COMMIT_INFO = "无法获取"
                            env.IMAGE_NAME  = "无法获取"
                            env.REASON = "构建分支不存在或认证失败"
                            throw(exc)
                        }
                    }
                }
            }

            stage('拉取ansible剧本') {
                steps {
                    dir("${WORKSPACE}/ansible_tmp"){
                        script {
                            try {
                                checkout(
                                    [$class: 'GitSCM', doGenerateSubmoduleConfigurations: false, submoduleCfg: [], extensions: [[$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]],
                                    branches: [[name: "master"]],userRemoteConfigs: [[url: "${env.GIT_URL_ANSIBLE}", credentialsId: "${env.GITLAB_AUTH_TOKEN}"]]]
                                )
                            }catch(exc) {
                                env.REASON = "拉取ansible剧本出错"
                                throw(exc)
                            }
                        }
                    }
                }
            }

            stage('编译项目') {
                when {
                    environment name: 'MODE',value: 'DEPLOY'
                }
                steps {
                    script {
                        try {
                            ansiColor('xterm') {
                                docker.image("${BUILD_BASE_IMAGE}").inside("${BUILD_ARGS}") {
                                    sh "$BUILD_COMMAND"
                                }
				                // sh "$BUILD_COMMAND" 这条是在宿主机上执行需要注释掉
                            }
                        }catch(exc) {
                            env.REASON = "编译项目出错"
                            throw(exc)
                        }
                    }
                }
            }

            stage ('并行如下任务'){
                parallel {
                    stage('定义部署主机列表'){
                        steps{
                            script{
                                try{
                                    sh '''
                                        OLD=${IFS}
                                        IFS='\n'
                                            if [ $REMOTE_HOST == "ALL" ];then
                                                echo "[remote]" > ${ANSIBLE_HOSTS}
                                                for i in ${HOSTS};do echo "$i ansible_ssh_user=root ansible_ssh_pass=zswl@2023 ansible_port=22" >> ${ANSIBLE_HOSTS};done
                                                sed -i '/ALL/d' ${ANSIBLE_HOSTS}
                                            else
                                                echo "[remote]" > ${ANSIBLE_HOSTS}
                                                echo "$REMOTE_HOST ansible_ssh_user=root ansible_ssh_pass=zswl@2023 ansible_port=22" >> ${ANSIBLE_HOSTS}
                                            fi
                                        IFS=${OLD}
                                    '''
                                }catch(exc) {
                                    env.Reason = "定义主机列表出错"
                                    throw(exc)
                                }
                            }
                        }
                    }
                    stage('定义忽略文件'){
                        steps{
                            script{
                                try{
                                    sh "echo -e \"${EXCLUDE_FILE}\" > ${WORKSPACE}/exclude_file.txt"
                                }catch(exc) {
                                    env.Reason = "定义忽略文件出错"
                                    throw(exc)
                                }
                            }
                        }
                    }
                }
            }

            stage('压缩制品') {
                when {
                    environment name: 'MODE',value: 'DEPLOY'
                }
                steps {
                    dir("${WORKSPACE}/${PROJECT_FILE_PATH}"){
                        script {
                            try {
                                sh "touch ${BUILD_TMP}/${_VERSION}.tar.bz2 && tar -zc -X \"${WORKSPACE}/exclude_file.txt\" -f ${BUILD_TMP}/${_VERSION}.tar.bz2 ./*"
                            }catch(exc) {
                                env.REASON = "压缩制品出错"
                                throw(exc)
                            }
                        }
                    }
                }
            }

            stage('向左<->向右') {
                stages {
                    stage('部署<向左') {
                        when {
                            environment name: 'MODE',value: 'DEPLOY'
                        }
                        steps {
                            dir("${ANSIBLE_BASE}"){
                            script {
                                try {
                                    ansiColor('xterm') {
                                        sh "echo \"${FREE_COMMAND}\" > ${ANSIBLE_BASE}/roles/deploy/files/free.sh"
                                        sh """
                                            ansible-playbook -vv -i ./deploy_hosts/${env.JOB_BASE_NAME}_hosts --tags "deploy" site.yml -e "SERVICE_NAME=${SERVICE_NAME} BUILD_TMP=${BUILD_TMP} _VERSION=${_VERSION} WEBROOT_DIR=${WEBROOT_DIR} WORKSPACE=${WORKSPACE}"
                                        """
                                    }
                                }catch(exc) {
                                    env.Reason = "项目部署步骤出错"
                                    throw(exc)
                                }
                            }
                            }
                        }
                    }
                    stage('向右>回滚') {
                        when {
                            environment name: 'MODE',value: 'ROLLBACK'
                        }
                        steps {
                            dir("${ANSIBLE_BASE}"){
                            script {
                                try {
                                    ansiColor('xterm') {
                                        sh "echo \"${FREE_COMMAND}\" > ${ANSIBLE_BASE}/roles/rollback/files/free.sh"
                                        sh """
                                            ansible-playbook -vv -i ./deploy_hosts/${env.JOB_BASE_NAME}_hosts --tags="rollback" site.yml -e "SERVICE_NAME=${SERVICE_NAME} WEBROOT_DIR=${WEBROOT_DIR} _VERSION=${ROLLBACK_VERSION}"
                                        """
                                    }
                                }catch(exc) {
                                    env.Reason = "项目回滚步骤出错"
                                    throw(exc)
                                }
                            }
                            }
                        }
                    }
                }
            }
            stage("版本号写入") {
                when {
                    environment name: 'MODE',value: 'DEPLOY'
                }
                steps {
                    script {
                        try {
                            env.FILE=sh (script:"ls ${VERSION_FILE}",returnStatus: true)
                            if("${env.FILE}" != "0") {
                                sh "echo \"${VERSION_KEY}=${_VERSION}\" > ${VERSION_FILE}"
                            }else {
                                sh 'sed -i "s#=#&${_VERSION},#" ${VERSION_FILE}'
                            }
                            env.NUMBER=sh (script: 'grep -o , ${VERSION_FILE} | wc -l', returnStdout: true).trim()
                            // 判断版本号是否为10个
                            if("${NUMBER}" == "10") {
                                sh '''
                                    sed -i "s#,`cut -d, -f11 ${VERSION_FILE}`##" ${VERSION_FILE}
                                '''
                            }
                        }catch(exc) {
                        env.REASON = "版本号写入出错"
                        throw(exc)
                        }
                    }
                }
            }
        }
        post {
            always {
                wrap([$class: 'BuildUser']){
                    script{
                        if ("${MODE}" == "DEPLOY") {
                            buildName "#${BUILD_ID}-${BRANCH}-${BUILD_USER}" // 更改构建名称
                            currentBuild.description = "提交者: ${COMMIT_USER}" // 添加说明信息
                            currentBuild.description += "\n构建主机: ${REMOTE_HOST}" // 添加说明信息
                            currentBuild.description += "\n提交ID: ${COMMIT_ID}" // 添加说明信息
                            currentBuild.description += "\n提交时间: ${COMMIT_TIME}" // 添加说明信息
                            currentBuild.description += "\n提交内容: ${COMMIT_INFO}" // 添加说明信息
                            sh "rm -f ${BUILD_TMP}/${_VERSION}.tar.bz2"
                        }else{
                            buildName "#${BUILD_ID}-${BRANCH}-${BUILD_USER}" // 更改构建名称
                            currentBuild.description = "回滚版本号为: ${ROLLBACK_VERSION}" // 添加说明信息
                        }
                        sh "printenv"
                    }
                }
            }
            success {
                wrap([$class: 'BuildUser']){
                    script{
                        // dingding.notice("构建成功")  钉钉通知，本次不需要
                        sh """
                            echo "构建成功🥳🥳🥳"
                        """
                    }
                }
            }
            failure {
                wrap([$class: 'BuildUser']){
                    script{
		                // dingding.notice("构建失败")  钉钉通知，本次不需要
                        sh """
                            echo "构建失败😤😤😤"
                        """
                    }
                }
            }
        }
    }
}

