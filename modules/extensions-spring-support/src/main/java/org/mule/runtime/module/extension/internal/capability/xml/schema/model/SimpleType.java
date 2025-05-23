/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.2-hudson-jaxb-ri-2.2-63-
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a>
// Any modifications to this file will be lost upon recompilation of the source schema.
// Generated on: 2011.06.14 at 03:58:12 PM GMT-03:00
//


package org.mule.runtime.module.extension.internal.capability.xml.schema.model;

import java.util.ArrayList;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.xml.bind.annotation.XmlSeeAlso;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;


/**
 * <p>
 * Java class for simpleType complex type.
 * <p/>
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * <p/>
 *
 * <pre>
 * &lt;complexType name="simpleType">
 *   &lt;complexContent>
 *     &lt;extension base="{http://www.w3.org/2001/XMLSchema}annotated">
 *       &lt;group ref="{http://www.w3.org/2001/XMLSchema}simpleDerivation"/>
 *       &lt;attribute name="final" type="{http://www.w3.org/2001/XMLSchema}simpleDerivationSet" />
 *       &lt;attribute name="name" type="{http://www.w3.org/2001/XMLSchema}NCName" />
 *       &lt;anyAttribute processContents='lax' namespace='##other'/>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "simpleType", propOrder = {"restriction", "list", "union"})
@XmlSeeAlso({TopLevelSimpleType.class, LocalSimpleType.class})
public abstract class SimpleType extends Annotated {

  protected Restriction restriction;
  protected List list;
  protected Union union;
  @XmlAttribute(name = "final")
  @XmlSchemaType(name = "simpleDerivationSet")
  protected java.util.List<String> _final;
  @XmlAttribute(name = "name")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  @XmlSchemaType(name = "NCName")
  protected String name;

  /**
   * Gets the value of the restriction property.
   *
   * @return possible object is {@link Restriction }
   */
  public Restriction getRestriction() {
    return restriction;
  }

  /**
   * Sets the value of the restriction property.
   *
   * @param value allowed object is {@link Restriction }
   */
  public void setRestriction(Restriction value) {
    this.restriction = value;
  }

  /**
   * Gets the value of the list property.
   *
   * @return possible object is {@link List }
   */
  public List getList() {
    return list;
  }

  /**
   * Sets the value of the list property.
   *
   * @param value allowed object is {@link List }
   */
  public void setList(List value) {
    this.list = value;
  }

  /**
   * Gets the value of the union property.
   *
   * @return possible object is {@link Union }
   */
  public Union getUnion() {
    return union;
  }

  /**
   * Sets the value of the union property.
   *
   * @param value allowed object is {@link Union }
   */
  public void setUnion(Union value) {
    this.union = value;
  }

  /**
   * Gets the value of the final property.
   * <p/>
   * <p/>
   * This accessor method returns a reference to the live list, not a snapshot. Therefore any modification you make to the
   * returned list will be present inside the JAXB object. This is why there is not a <CODE>set</CODE> method for the final
   * property.
   * <p/>
   * <p/>
   * For example, to add a new item, do as follows:
   *
   * <pre>
   * getFinal().add(newItem);
   * </pre>
   * <p/>
   * <p/>
   * <p/>
   * Objects of the following type(s) are allowed in the list {@link String }
   */
  public java.util.List<String> getFinal() {
    if (_final == null) {
      _final = new ArrayList<String>();
    }
    return this._final;
  }

  /**
   * Gets the value of the name property.
   *
   * @return possible object is {@link String }
   */
  public String getName() {
    return name;
  }

  /**
   * Sets the value of the name property.
   *
   * @param value allowed object is {@link String }
   */
  public void setName(String value) {
    this.name = value;
  }

}
