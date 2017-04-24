/*
 * unpuzzle
 *
 * Copyright 2014  Andrey Hihlovskiy.
 *
 * See the file "LICENSE" for copying and usage permission.
 */
package org.akhikhl.unpuzzle.osgi2maven

import groovy.xml.NamespaceBuilder

/**
 * Deploys OSGI bundle (jar or directory) to maven repository
 * @author akhikhl
 */
class Deployer {
    
  private static URL fileToUrl(File f) {
    String s = f.toURI().toString()
    if(s.endsWith('/'))
      s = s[0..-2]
    new URL(s)    
  }
  
  private static URL stringToUrl(String s) {
    if(!s.startsWith('file:') && !s.startsWith('http:') && !s.startsWith('https:'))
      s = new File(s).toURI().toString()
    if(s.endsWith('/'))
      s = s[0..-2]
    new URL(s)    
  }

  final Map deployerOptions
  final URL repositoryUrl
  private ant
  private File workFolder

  /**
   * Constructs Deployer with the specified parameters.
   * @param deployerOptions - may contain properties "user" and "password"
   * @param localRepositoryDir - target maven repository
   */
  Deployer(Map deployerOptions = [:], File localRepositoryDir) {
    this(deployerOptions, fileToUrl(localRepositoryDir))
  }

  Deployer(Map deployerOptions = [:], String repositoryUrl) {
    this(deployerOptions, stringToUrl(repositoryUrl))
  }

  /**
   * Constructs Deployer with the specified parameters.
   * @param deployerOptions - may contain properties "user" and "password"
   * @param repositoryUrl - URL of the target maven repository
   */
  Deployer(Map deployerOptions = [:], URL repositoryUrl) {
    this.deployerOptions = ([:] << deployerOptions).asImmutable()
	
	ClassLoader loader = this.getClass().getClassLoader()
    if (this.deployerOptions.ant) {
	  System.out.prinln('Unsing supported ant ' + this.deployerOptions.ant)
	  this.ant = this.deployerOptions.ant
	}
    else {
	  // create new AntBuilder instance
	  System.out.println('Using current thread classloader to create a new groovy.util.AntBuilder');
	  this.ant = Class.forName('groovy.util.AntBuilder', true, loader).newInstance()
	}
		
	// ensure presence of Pom and Deploy tasks
	defineAntTask('pom', 'org.apache.maven.artifact.ant.Pom', loader)
	defineAntTask('deploy', 'org.apache.maven.artifact.ant.DeployTask', loader)
	
    this.repositoryUrl = repositoryUrl
    workFolder = new File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
    workFolder.deleteOnExit()
  }
  
  void defineAntTask(String taskName, String taskClassName, ClassLoader loader) {
	Class<?> pomClass = loader.loadClass(taskClassName)
	if (pomClass == null) {
	  URL[] clURLs = loader.getURLs()
	  StringBuilder sb = new StringBuilder()
	  sb.append('Missing class ')
	  sb.append(taskClassName)
	  sb.append('\nEnsure package maven-ant-tasks is in classpath')
	  for (URL uri: clURLs) {		
	    sb.append('\n')
	    sb.append(uri)
	  }
	  throw new ClassNotFoundException(sb.toString())
	}
	this.ant.getAntProject().addTaskDefinition('pom', pomClass)
  }

  /**
   * Deploys the specified bundle with the specified POM to target maven repository.
   * @param options - may contain sourceFile (of type java.io.File), pointing to sources jar.
   * @param pomStruct - contains POM that will be used for deployment
   * @param bundleFileOrDirectory - jar-file or directory, containing OSGI bundle
   */
  void deployBundle(Map options = [:], Pom pomStruct, File bundleFileOrDirectory) {
    workFolder.mkdirs()
    def pomFile = new File(workFolder, 'myPom.xml')
    File bundleFile	
    if (bundleFileOrDirectory.isDirectory()) {
      pomStruct.packaging = 'jar'
      pomFile.text = pomStruct.toString()
      File zipFile = new File(workFolder, "${pomStruct.artifact}-${pomStruct.version}.jar")
      ant.zip(basedir: bundleFileOrDirectory, destfile: zipFile)
      bundleFile = zipFile
    }
    else {
      pomFile.text = pomStruct.toString()
      bundleFile = bundleFileOrDirectory
    }
    File sourceFile = options.sourceFile
    if(sourceFile?.isDirectory()) {
      File zipFile = new File(workFolder, sourceFile.name + '.jar')
      ant.zip(basedir: sourceFile, destfile: zipFile)
      sourceFile = zipFile
    }

    ant.with {
	
	  taskdef name: 'pom', classname: 'org.apache.maven.artifact.ant.Pom'
	  taskdef name: 'deploy', classname: 'org.apache.maven.artifact.ant.DeployTask'
	  
      pom id: 'mypom', file: pomFile
      deploy file: bundleFile, {
        pom refid: 'mypom'
        if(sourceFile)
          attach file: sourceFile, type: 'jar', classifier: 'sources'
        remoteRepository url: repositoryUrl.toString(), {
          if(deployerOptions.user && deployerOptions.password)
            authentication username: deployerOptions.user, password: deployerOptions.password
        }
      }
    }
  }
}

