/* IMPORTANT: This file is deprecated in favor of .travis.yml */

node {
  stage 'Checkout'
  echo "Branch is: ${env.BRANCH_NAME}"
  checkout scm
  step ([$class: 'CopyArtifact', projectName: 'gameserver-core', filter: 'build/libs/*.jar', flatten: true]);

  def versions = ['1.8', '1.8.9']
  for (v in versions) {
    stage "Build ${v}"
    sh "./gradlew --no-daemon clean"
    sh "./gradlew --no-daemon build -Penv=production -Pmctarget=${v} -PBUILD_NUMBER=${env.BUILD_NUMBER}"
    step([$class: 'ArtifactArchiver', artifacts: 'build/libs/*.jar', excludes: '**/*-sources.jar', onlyIfSuccessful: true, fingerprint: false])
  }
}
