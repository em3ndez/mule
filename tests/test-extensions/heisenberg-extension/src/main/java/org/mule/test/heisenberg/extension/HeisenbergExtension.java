/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.heisenberg.extension;

import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.sdk.api.meta.Category.SELECT;
import static org.mule.sdk.api.meta.ExternalLibraryType.NATIVE;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import static com.google.common.collect.ImmutableList.copyOf;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.extension.api.annotation.Alias;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.Sources;
import org.mule.runtime.extension.api.annotation.SubTypeMapping;
import org.mule.runtime.extension.api.annotation.connectivity.ConnectionProviders;
import org.mule.runtime.extension.api.annotation.deprecated.Deprecated;
import org.mule.runtime.extension.api.annotation.notification.NotificationActions;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.param.RefName;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.runtime.source.BackPressureContext;
import org.mule.sdk.api.annotation.Export;
import org.mule.sdk.api.annotation.Extension;
import org.mule.sdk.api.annotation.ExternalLib;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.sdk.api.annotation.OnException;
import org.mule.sdk.api.annotation.error.ErrorTypes;
import org.mule.sdk.api.annotation.param.display.Example;
import org.mule.sdk.api.annotation.param.display.Text;
import org.mule.sdk.api.meta.ExpressionSupport;
import org.mule.test.heisenberg.extension.exception.SdkHeisenbergConnectionExceptionEnricher;
import org.mule.test.heisenberg.extension.model.BarberPreferences;
import org.mule.test.heisenberg.extension.model.CarDealer;
import org.mule.test.heisenberg.extension.model.CarWash;
import org.mule.test.heisenberg.extension.model.DifferedKnockableDoor;
import org.mule.test.heisenberg.extension.model.HankSchrader;
import org.mule.test.heisenberg.extension.model.HealthStatus;
import org.mule.test.heisenberg.extension.model.Investment;
import org.mule.test.heisenberg.extension.model.KnockeableDoor;
import org.mule.test.heisenberg.extension.model.PersonalInfo;
import org.mule.test.heisenberg.extension.model.Ricin;
import org.mule.test.heisenberg.extension.model.Weapon;
import org.mule.test.heisenberg.extension.model.drugs.Drug;
import org.mule.test.heisenberg.extension.model.drugs.Meta;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

@Extension(name = HeisenbergExtension.HEISENBERG, category = SELECT)
@JavaVersionSupport({JAVA_21, JAVA_17})
@Operations({HeisenbergOperations.class, MoneyLaunderingOperation.class,
    KillingOperations.class, HeisenbergScopes.class, HeisenbergRouters.class, HeisenbergOperationLifecycleValidator.class})
@OnException(SdkHeisenbergConnectionExceptionEnricher.class)
@ConnectionProviders({HeisenbergConnectionProvider.class, SecureHeisenbergConnectionProvider.class})
@Sources({HeisenbergSource.class, HeisenbergSourceAllOptionalCallbacks.class, DEARadioSource.class,
    AsyncHeisenbergSource.class})
@org.mule.sdk.api.annotation.Sources({ReconnectableHeisenbergSource.class, HeisenbergClusterSource.class,
    IgnoredHeisenbergSource.class, SdkIgnoredHeisenbergSource.class, ReconnectableHeisenbergSdkSource.class})
@Export(classes = {HeisenbergExtension.class, DifferedKnockableDoor.class, HeisenbergErrors.class}, resources = "methRecipe.json")
@SubTypeMapping(baseType = Weapon.class, subTypes = {Ricin.class})
@SubTypeMapping(baseType = Drug.class, subTypes = {Meta.class})
@SubTypeMapping(baseType = Investment.class, subTypes = {CarWash.class, CarDealer.class})
@ExternalLib(name = HeisenbergExtension.HEISENBERG_LIB_NAME, description = HeisenbergExtension.HEISENBERG_LIB_DESCRIPTION,
    nameRegexpMatcher = HeisenbergExtension.HEISENBERG_LIB_FILE_NAME,
    requiredClassName = HeisenbergExtension.HEISENBERG_LIB_CLASS_NAME, type = NATIVE,
    coordinates = "org.mule.libs:this-is-a-lib:dll:1.0.0")
@Deprecated(message = "This extension has been deprecated because Breaking Bad has ended, use Better Call Saul extension.",
    since = "1.4.0")
@ErrorTypes(HeisenbergErrors.class)
@NotificationActions(HeisenbergNotificationAction.class)
public class HeisenbergExtension implements Lifecycle {

  public static final String HEISENBERG = "Heisenberg";
  public static final String HEISENBERG_LIB_NAME = "Heisenberg.so";
  public static final String HEISENBERG_LIB_DESCRIPTION = "Native Heisenberg support";
  public static final String HEISENBERG_LIB_FILE_NAME = "heisenberg.so";
  public static final String HEISENBERG_LIB_CLASS_NAME = "org.heisenberg.HeisenbergJNI";

  public static final String AGE = "50";
  public static final String RICIN_GROUP_NAME = "Dangerous-Ricin";
  public static final String RICIN_PACKS_SUMMARY = "A set of ricin packs";
  public static final String PERSONAL_INFORMATION_GROUP_NAME = "Personal Information";
  public static final String INLINE_BARBER_PREFERENCES = "Inline Barber Preferences";
  public static final String INLINE_SECOND_BARBER_PREFERENCES = "Inline Second Barber Preferences";
  public static final String BROTHER_IN_LAW = "Brother in law";
  public static final String PARAMETER_OVERRIDED_DISPLAY_NAME = "Parameter Custom Display Name";
  public static final String PARAMETER_ORIGINAL_OVERRIDED_DISPLAY_NAME = "weaponValueMap";
  public static final String LAB_ADDRESS_EXAMPLE = "308 Negra Arroyo Lane, Albuquerque, New Mexico, 87104";

  private int initialise = 0;
  private int start = 0;
  private int stop = 0;
  private int dispose = 0;
  public static volatile int sourceTimesStarted = 0;

  @Inject
  private ExtensionManager extensionManager;

  @Inject
  private ObjectStoreManager objectStoreManager;

  @RefName
  private String configName;

  @Parameter
  @Optional
  private List<PersonalInfo> familyInformations;

  @Parameter
  private List<String> enemies = new LinkedList<>();

  @Parameter
  private final List<Long> monthlyIncomes = new LinkedList<>();

  @Parameter
  private boolean cancer;

  @Parameter
  @Optional
  private Map<String, Long> recipe;

  @Parameter
  @Optional
  private Map<String, List<String>> deathsBySeasons;

  @ParameterGroup(name = RICIN_GROUP_NAME)
  private RicinGroup ricinGroup;

  @ParameterGroup(name = PERSONAL_INFORMATION_GROUP_NAME)
  @DisplayName("Personal Info")
  private final PersonalInfo personalInfo = new PersonalInfo();

  @Parameter
  private BigDecimal money;

  @Parameter
  @Optional
  private final Weapon weapon = new Ricin();

  @Parameter
  @Optional
  private List<? extends Weapon> wildCardWeapons;

  // Since the generic of this parameter is a wildcard, its children type cannot be determined.
  // Because of this, the dslConfiguration for this parameter will have `allowInlineDefinition=false`
  @Parameter
  @Optional
  private List<?> wildCards;

  @Parameter
  @Optional
  @DisplayName(PARAMETER_OVERRIDED_DISPLAY_NAME)
  private Map<String, Weapon> weaponValueMap;

  /**
   * Doors I might knock on but still haven't made up mind about
   */
  @Parameter
  @Optional
  private Map<String, KnockeableDoor> candidateDoors;

  @Parameter
  @org.mule.sdk.api.annotation.param.Optional(defaultValue = "CANCER")
  private HealthStatus initialHealth;

  @Parameter
  @Alias("finalHealth")
  private HealthStatus endingHealth;

  @Parameter
  @org.mule.sdk.api.annotation.Expression(ExpressionSupport.REQUIRED)
  @Optional
  @Example(LAB_ADDRESS_EXAMPLE)
  private String labAddress;

  @Parameter
  @Expression(NOT_SUPPORTED)
  @Optional
  @Text
  private String firstEndevour;

  @Parameter
  @Optional
  private List<HealthStatus> healthProgressions;

  @ParameterGroup(name = INLINE_BARBER_PREFERENCES, showInDsl = true)
  private BarberPreferences barberPreferences;

  @ParameterGroup(name = INLINE_SECOND_BARBER_PREFERENCES, showInDsl = true)
  private BarberPreferences secondBarberPreferences;


  @ParameterGroup(name = BROTHER_IN_LAW, showInDsl = true)
  @DisplayName("Brother in law")
  private HankSchrader brotherInLaw;

  private final List<BackPressureContext> backPressureContexts = new LinkedList<>();
  private final List<org.mule.sdk.api.runtime.source.BackPressureContext> sdkBackPressureContexts = new LinkedList<>();

  public void onBackPressure(BackPressureContext ctx) {
    synchronized (backPressureContexts) {
      backPressureContexts.add(ctx);
    }
  }

  public void onBackPressure(org.mule.sdk.api.runtime.source.BackPressureContext ctx) {
    synchronized (sdkBackPressureContexts) {
      sdkBackPressureContexts.add(ctx);
    }
  }

  public List<BackPressureContext> getBackPressureContexts() {
    synchronized (backPressureContexts) {
      return copyOf(backPressureContexts);
    }
  }

  public List<org.mule.sdk.api.runtime.source.BackPressureContext> getSdkBackPressureContexts() {
    synchronized (sdkBackPressureContexts) {
      return copyOf(sdkBackPressureContexts);
    }
  }

  @Override
  public void initialise() throws InitialisationException {
    initialise++;
  }

  @Override
  public void start() throws MuleException {
    start++;
  }

  @Override
  public void stop() throws MuleException {
    stop++;
  }

  @Override
  public void dispose() {
    dispose++;
  }

  public BarberPreferences getBarberPreferences() {
    return barberPreferences;
  }

  public BarberPreferences getSecondBarberPreferences() {
    return secondBarberPreferences;
  }

  public List<HealthStatus> getHealthProgression() {
    return healthProgressions;
  }

  public Map<String, Weapon> getWeaponValueMap() {
    return weaponValueMap;
  }

  public ExtensionManager getExtensionManager() {
    return extensionManager;
  }

  public List<String> getEnemies() {
    return enemies;
  }

  public void setEnemies(List<String> enemies) {
    this.enemies = enemies;
  }

  public boolean isCancer() {
    return cancer;
  }

  public BigDecimal getMoney() {
    return money;
  }

  public Map<String, Long> getRecipe() {
    return recipe;
  }

  public RicinGroup getRicinGroup() {
    return ricinGroup;
  }

  public Map<String, KnockeableDoor> getCandidateDoors() {
    return candidateDoors;
  }

  public int getInitialise() {
    return initialise;
  }

  public int getStart() {
    return start;
  }

  public int getStop() {
    return stop;
  }

  public int getDispose() {
    return dispose;
  }

  public HealthStatus getInitialHealth() {
    return initialHealth;
  }

  public HealthStatus getEndingHealth() {
    return endingHealth;
  }

  public PersonalInfo getPersonalInfo() {
    return personalInfo;
  }

  void setEndingHealth(HealthStatus endingHealth) {
    this.endingHealth = endingHealth;
  }

  void setMoney(BigDecimal money) {
    this.money = money;
  }

  public String getLabAddress() {
    return labAddress;
  }

  public String getFirstEndevour() {
    return firstEndevour;
  }

  public Weapon getWeapon() {
    return weapon;
  }

  public List<Long> getMonthlyIncomes() {
    return monthlyIncomes;
  }

  public Map<String, List<String>> getDeathsBySeasons() {
    return deathsBySeasons;
  }

  public String getConfigName() {
    return configName;
  }

  public List<? extends Weapon> getWildCardWeapons() {
    return wildCardWeapons;
  }

  public ObjectStoreManager getObjectStoreManager() {
    return objectStoreManager;
  }
}
