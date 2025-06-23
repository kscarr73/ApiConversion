# ApiConversion: Java Library for ApiModel Conversion

Convert [ApiModels](https://github.com/kscarr73/ApiCore_jre21) from one Model to another. This project 
can be used in a DTO environment, to create an ApiObject for use in a Database or to transform to another object.

- **convertObject**: Converts Based on the Subject, so it only iterates the fields that exist in the Subject object.
- **convertObjectV2**: Iterates over the Convert Object, so all fields in the Convert object are tested against the Subject.  You can add `required` and `type` that will
validate the field in Subject to the correct type.

## Configuration

The Convert process takes a Configuration of the format `field: {{new field}}` or `field: {{object}}`.

The object format has the following fields:

  - **field**: The new field to name the result
  - **method**: The method to use to process the field
  - **required** (V2): true/false is the field required in Subject
  - **type** (V2): Is the field the type.  Enum: string, integer, long, boolean, object, arraylist, stringarray
  - **default** (V2): If the subject value is not set, then use the default 

### Default Methods

  - **json2String**: Process ApiObject to JSON string
  - **string2Json**: Process a String to ApiObject
  - **bool2Int**: Convert a boolean to 0 - False, 1 - True
  - **int2Bool**: Convert Int 0 - False, 1 - True to a Boolean Value

### Custom Methods

You can add your own processing methods.  It is a good idea to add both the main method, and the reverse method as well.

#### Example Custom Method

```java
public class MyCustomMethod {
    public MyCustomMethod() {
        ApiObjectConverter.getMethods().put("boolToInt", this::convertFieldFromBoolean);
        ApiObjectConverter.getMethods().put("intToBool", this::convertFieldToBoolean);

        ApiObjectConverter.getReverseMethods().setString("boolToInt", "intToBool");
        ApiObjectConverter.getReverseMethods().setString("intToBool", "boolToInt");
    }

    private void convertFieldToBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.getType(fieldFrom) == ApiObject.TYPE_INTEGER) {
            Boolean bTest = false;

            if (0 != from.getInteger(fieldFrom)) {
                bTest = true;
            }

            to.setBoolean(processObj.getString("field"), bTest);
        }
    }

    private void convertFieldFromBoolean(ApiObject from, ApiObject to, String fieldFrom, ApiObject processObj) throws ApiException {
        if (from.containsKey(fieldFrom)) {
            to.setInteger(processObj.getString("field"), from.isSet(fieldFrom) ? 1 : 0);
        }
    }
}
```
## Example V1

To convert a REST call into a Database object for use in [SsDbUtils](https://github.com/kscarr73/SsDbUtils_jre21).

```yaml
CONVERT_CONTACT:
  id: contact_id
  firstName: first_name
  lastName: last_name
  properties: 
    field: properties_json
    method: json2String
```

This would process a contact, with a JSON properties field stored in the database.

```java
public class ContactService {
    ApiObject contactToDb;
    ApiObject dbToContact;
    ApiObjectConverter apiConvert;

    public void configure() {
        apiConvert = ApiObjectConverter.getInstance();
        
        // Retrieve CONVERT OBJECT from ConfigProvider
        contactToDb = ConfigProvider.getInstance()
           .getConfig().getObject("CONVERT_CONTACT");

        // Reverse Object coming from database
        dbToConact = apiConvert.reverseConvertObject(contactToDb);
    }

    public ApiObject saveContact(ApiObject contact) {
        ApiObject contactDb = apiConvert
           .convertObject(contact, contactToDb);

        ApiObject sqlContact = DataManager.getInstance()
           .saveIntegerKey(DataManager.DEFAULT, 
           "contacts", "id", contactDb);

        return apiConvert.convertObject(sqlContact, dbToContact);
    }

}
```

## Example V2

To convert a REST call into a Database object for use in [SsDbUtils](https://github.com/kscarr73/SsDbUtils_jre21).

```yaml
CONVERT_CONTACT:
  id: contact_id
  firstName: first_name
  lastName: 
    field: last_name
    type: string
    required: true
  address:
    field: contactAddress
    type: object
    required: true
  # When using V2, you can add multiple entries with 
  # the same field using `#` and a number. This keeps the entries
  # unique.  When processing everything 
  # `#` and after is removed
  address#1:
    field: latitudeLongitude
    method: lookupLatLong
  properties: 
    field: properties_json
    method: json2String
```

This would process a contact, with a JSON properties field stored in the database.

```java
public class ContactService {
    ApiObject contactToDb;
    ApiObject dbToContact;
    ApiObjectConverter apiConvert;

    public void configure() {
        apiConvert = ApiObjectConverter.getInstance();
        
        // Retrieve CONVERT OBJECT from ConfigProvider
        contactToDb = ConfigProvider.getInstance()
           .getConfig().getObject("CONVERT_CONTACT");

        // Reverse Object coming from database
        dbToConact = apiConvert.reverseConvertObject(contactToDb);
    }

    public ApiObject saveContact(ApiObject contact) {
        ApiObject contactDb = apiConvert
           .convertObjectV2(contact, contactToDb);

        ApiObject sqlContact = DataManager.getInstance()
           .saveIntegerKeyV2(DataManager.DEFAULT, 
           "contacts", "id", contactDb);

        return apiConvert.convertObject(sqlContact, dbToContact);
    }

}
```
