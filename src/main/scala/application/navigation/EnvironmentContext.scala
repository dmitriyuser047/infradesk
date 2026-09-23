package ru.bitec.app.ops
package application.navigation

import domain.enviroment.Environment
import domain.project.Project

final case class EnvironmentContext(project: Project, environment: Environment)
