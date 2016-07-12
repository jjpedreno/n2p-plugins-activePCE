# n2p-plugins-activePCE
Active Stateful Prototype PCE Plugin for Net2Plan

This repository contains the first functional prototype of an active stateful PCE, and PCC emulator developed as a Net2Plan plugin. This plugins has been coded according with the standards defined in:
* [RFC5440 - Path Computation Element Protocol](https://tools.ietf.org/html/rfc5440)
* [draft-ietf-pce-stateful-pce-14 - PCEP Extensions for Stateful PCE](https://www.ietf.org/id/draft-ietf-pce-stateful-pce-14.txt)
* [RFC7752 - North-Bound Distribution of Link-State and Traffic Engineering (TE) Information Using BGP](https://tools.ietf.org/html/rfc7752)
* [RFC4760 - Multiprotocol Extensions for BGP-4](https://tools.ietf.org/html/rfc4760)

A thorough description of the operation and case studies is described in the paper:

Jose-Luis Izquierdo-Zaragoza, Jose-Juan Pedreno-Manresa, Pablo Pavon-Marino, Oscar Gonzalez de Dios and Victor Lopez, **“Dynamic Operation of an IP/MPLS-over-WDM Network Using an Active Stateful BGP/LS-Enabled Multilayer PCE,”** in *Proceedings of the 18th International Conference on Transparent Optical Networks (ICTON 2016)*, Trento (Italy), July 2016.

## Requirements
The user should be familiar with Java, and the use and development of Net2Plan.
The project uses Maven, so all dependencies are self-managed. All dependencies are included as a local reository (folder `repo`) except for [netphony-protocols](https://github.com/telefonicaid/netphony-network-protocols).

##Installation
Net2Plan is available at [www.net2plan.com](http://www.net2plan.com)
**This plugin is compatible with version 0.4.0**

To generate the JAR use the command: `mvn clean compile assembly:single` then put the resulting artifact (`net2plan-activePCE-jar-with-dependencies.jar`) in the Net2Plan subfolder `plugins`

##Known issues
Due to a bug in [netphony-network-protocols](https://github.com/telefonicaid/netphony-network-protocols) v.1.3.0 the Java Virtual Machine may enter an infinite loop when decoding a PCEPUpdate packet that contains several ERO objects. This situation occurs when restoring multiple lightpaths affected by the same fiber failure.
