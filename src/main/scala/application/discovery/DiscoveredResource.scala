package ru.bitec.app.ops
package application.discovery

import domain.resource.ResourceData

final case class DiscoveredExternalIdentity(
                                             externalType: String,
                                             externalId: String
                                           )

final case class DiscoveredResource(
                                     externalType: String,
                                     externalId: String,
                                     resourceTypeCode: String,
                                     code: String,
                                     name: String,
                                     parentExternalIdentity: Option[DiscoveredExternalIdentity] = None,
                                     data: ResourceData = ResourceData.empty
                                   )
