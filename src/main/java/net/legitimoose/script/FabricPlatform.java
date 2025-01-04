// SPDX-FileCopyrightText: © 2022-2024 Greg Christiana <maxuser@minescript.net>
// SPDX-License-Identifier: GPL-3.0-only

package net.legitimoose.script;

class FabricPlatform implements Platform {
  @Override
  public String modLoaderName() {
    return "Fabric";
  }
}
