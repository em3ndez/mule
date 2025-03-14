/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.resources.documentation;

import static org.mule.runtime.module.extension.internal.ExtensionProperties.EXTENSION_DESCRIPTIONS_FILE_NAME_MASK;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;

import org.mule.apache.xml.serialize.OutputFormat;
import org.mule.apache.xml.serialize.XMLSerializer;
import org.mule.runtime.module.extension.api.resources.documentation.ExtensionDescriptionsSerializer;
import org.mule.runtime.module.extension.api.resources.documentation.XmlExtensionDocumentation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

public class DefaultExtensionDescriptionsSerializer
    implements ExtensionDescriptionsSerializer {

  private JAXBContext jaxbContext;
  private Marshaller marshaller;
  private Unmarshaller unmarshaller;

  public DefaultExtensionDescriptionsSerializer() {
    final ClassLoader tccl = currentThread().getContextClassLoader();
    currentThread().setContextClassLoader(DefaultExtensionDescriptionsSerializer.class.getClassLoader());
    try {
      jaxbContext = JAXBContext.newInstance(DefaultXmlExtensionDocumentation.class);
      marshaller = jaxbContext.createMarshaller();
      unmarshaller = jaxbContext.createUnmarshaller();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      currentThread().setContextClassLoader(tccl);
    }
  }

  public synchronized String serialize(XmlExtensionDocumentation dto) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
      marshaller.marshal(dto, getXmlSerializer(out).asContentHandler());

      return out.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized XmlExtensionDocumentation deserialize(String xml) {
    try {
      return (DefaultXmlExtensionDocumentation) unmarshaller.unmarshal(new ByteArrayInputStream(xml.getBytes()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized XmlExtensionDocumentation deserialize(InputStream xml) {
    try {
      return (DefaultXmlExtensionDocumentation) unmarshaller.unmarshal(xml);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private XMLSerializer getXmlSerializer(OutputStream out) {
    OutputFormat of = new OutputFormat();

    of.setCDataElements(new String[] {"^description"});
    of.setIndenting(true);

    XMLSerializer serializer = new XMLSerializer(of);
    serializer.setOutputByteStream(out);

    return serializer;
  }

  public String getFileName(String extensionName) {
    String key = extensionName.replace(" ", "-").toLowerCase();
    return format(EXTENSION_DESCRIPTIONS_FILE_NAME_MASK, key);
  }
}
