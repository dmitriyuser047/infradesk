package ru.bitec.app.ops
package application.discovery

final case class DiscoveredResource(
                                     externalType: String,
                                     externalId: String,
                                     resourceTypeCode: String,
                                     code: String,
                                     name: String
                                   )