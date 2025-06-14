/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.extension.config;

import static org.mule.runtime.api.message.Message.of;
import static org.mule.runtime.api.util.collection.SmallMap.of;
import static org.mule.runtime.core.api.event.EventContextFactory.create;
import static org.mule.test.heisenberg.extension.model.types.WeaponType.FIRE_WEAPON;
import static org.mule.test.module.extension.internal.util.ExtensionsTestUtils.getConfigurationFromRegistry;

import static java.util.Arrays.asList;

import static org.apache.commons.lang3.ArrayUtils.toObject;

import org.mule.runtime.api.artifact.Registry;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.test.heisenberg.extension.HeisenbergExtension;
import org.mule.test.heisenberg.extension.model.HealthStatus;
import org.mule.test.heisenberg.extension.model.KnockeableDoor;
import org.mule.test.heisenberg.extension.model.Ricin;
import org.mule.test.module.extension.AbstractHeisenbergConfigTestCase;

import java.util.List;
import java.util.Map;

public abstract class AbstractConfigParserTestCase extends AbstractHeisenbergConfigTestCase {

  protected static final String HEISENBERG_BYNAME = "heisenberg";
  protected static final String HEISENBERG_EXPRESSION = "expressionHeisenberg";
  protected static final String HEISENBERG_EXPRESSION_BYREF = "expressionHeisenbergByRef";

  protected static final Long MICROGRAMS_PER_KILO = 22L;
  protected static final String LIDIA = "Lidia";
  protected static final String STEVIA_COFFE_SHOP = "Stevia coffe shop";
  protected static final String POLLOS_HERMANOS = "pollos hermanos";
  protected static final String GUSTAVO_FRING = "Gustavo Fring";
  protected static final String KRAZY_8 = "Krazy-8";
  protected static final String JESSE_S = "Jesse's";
  protected static final int METHYLAMINE_QUANTITY = 75;
  protected static final int PSEUDOEPHEDRINE_QUANTITY = 0;
  protected static final String P2P = "P2P";
  protected static final int P2P_QUANTITY = 25;
  protected static final String HANK = "Hank";
  protected static final String MONEY = "1000000";
  protected static final String SKYLER = "Skyler";
  protected static final String SAUL = "Saul";
  protected static final String WHITE_ADDRESS = "308 Negra Arroyo Lane";
  protected static final String SHOPPING_MALL = "Shopping Mall";
  protected static final HealthStatus INITIAL_HEALTH = HealthStatus.CANCER;
  protected static final HealthStatus FINAL_HEALTH = HealthStatus.DEAD;
  protected static final Ricin WEAPON = new Ricin();

  protected static final String SEASON_1_KEY = "s01";
  protected static final String SEASON_2_KEY = "s02";
  protected static final List<Long> MONTHLY_INCOMES = asList(toObject(new long[] {12000, 500}));
  protected static final Map<String, List<String>> DEATHS_BY_SEASON = of(SEASON_1_KEY, asList("emilio", "domingo"),
                                                                         SEASON_2_KEY, asList("tuco", "tortuga"));

  private static Registry staticRegistry;

  @Override
  protected void doSetUp() throws Exception {
    super.doSetUp();
    staticRegistry = this.registry;
  }

  @Override
  protected void doTearDown() throws Exception {
    staticRegistry = null;
    super.doTearDown();
  }

  public static KnockeableDoor getDoor() throws Exception {
    return staticRegistry.<KnockeableDoor>lookupByName("door").get();
  }

  protected HeisenbergExtension lookupHeisenberg(String key) throws Exception {
    CoreEvent heisenbergEvent = null;
    try {
      heisenbergEvent = getHeisenbergEvent();
      return lookupHeisenberg(key, heisenbergEvent);
    } finally {
      if (heisenbergEvent != null) {
        ((BaseEventContext) heisenbergEvent.getContext()).success();
      }
    }
  }

  protected HeisenbergExtension lookupHeisenberg(String key, CoreEvent event) throws Exception {
    return getConfigurationFromRegistry(key, event, muleContext);
  }

  protected CoreEvent getHeisenbergEvent() throws Exception {
    WEAPON.setMicrogramsPerKilo(10L);
    CoreEvent event = CoreEvent.builder(create(getTestFlow(muleContext), TEST_CONNECTOR_LOCATION)).message(of(""))
        .addVariable("lidia", LIDIA)
        .addVariable("myName", HeisenbergExtension.HEISENBERG)
        .addVariable("age", HeisenbergExtension.AGE)
        .addVariable("microgramsPerKilo", MICROGRAMS_PER_KILO)
        .addVariable("steviaCoffeShop", STEVIA_COFFE_SHOP)
        .addVariable("pollosHermanos", POLLOS_HERMANOS)
        .addVariable("gustavoFring", GUSTAVO_FRING)
        .addVariable("krazy8", KRAZY_8)
        .addVariable("jesses", JESSE_S)
        .addVariable("methylamine", METHYLAMINE_QUANTITY)
        .addVariable("pseudoephedrine", PSEUDOEPHEDRINE_QUANTITY)
        .addVariable("p2p", P2P_QUANTITY)
        .addVariable("hank", HANK)
        .addVariable("money", MONEY)
        .addVariable("skyler", SKYLER)
        .addVariable("saul", SAUL)
        .addVariable("whiteAddress", WHITE_ADDRESS)
        .addVariable("shoppingMall", SHOPPING_MALL)
        .addVariable("initialHealth", INITIAL_HEALTH)
        .addVariable("finalHealth", FINAL_HEALTH)
        .addVariable("weaponType", FIRE_WEAPON)
        .addVariable("weapon", WEAPON)
        .build();

    return event;
  }

  @Override
  protected String getConfigFile() {
    return "operations/heisenberg-config.xml";
  }
}
