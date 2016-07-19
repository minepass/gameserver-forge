node {
  stage 'Checkout'
  echo "Branch is: ${env.BRANCH_NAME}"
  checkout scm
  step ([$class: 'CopyArtifact', projectName: 'gameserver-core', filter: 'build/libs/*.jar', flatten: true]);

  stage 'Setup'
  sh "./gradlew clean"

  def versions = ['1.9', '1.9.4']
  for (v in versions) {
    stage "Build ${v}"
    sh "./gradlew build -Penv=production -Pmctarget=${v} -PBUILD_NUMBER=${env.BUILD_NUMBER}"
  }

  stage 'Archive'
  step([$class: 'ArtifactArchiver', artifacts: 'build/libs/*.jar', excludes: '**/*-sources.jar', onlyIfSuccessful: true, fingerprint: false])
}
