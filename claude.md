# Claude Instructions

For all development work in this repository, follow these guidelines:

## Frontend Development

### Code Structure
- **All code goes into `index.html`** - Keep the entire application in a single HTML file
- **No external libraries allowed** - Use vanilla JavaScript and native browser APIs only

### Architecture
- **Use Web Components** - All custom UI elements should be implemented as Web Components
- **Extend HTMLElement** - Create custom elements by extending the HTMLElement class
- Use the Custom Elements API for component registration
- Follow Web Components best practices for encapsulation and reusability

### Example Structure
```javascript
class MyComponent extends HTMLElement {
  constructor() {
    super();
    // Initialize component
  }

  connectedCallback() {
    // Component mounted to DOM
  }
}

customElements.define('my-component', MyComponent);
```

## Backend Development

### Technology Stack
- **Use latest Java (Java 21+)** - All backend code should be written in the latest stable Java version
- Leverage modern Java features such as:
  - Virtual threads
  - Pattern matching
  - Records
  - Sealed classes
  - Enhanced switch expressions

### Best Practices
- Follow standard Java conventions and naming patterns
- Use appropriate frameworks as needed (Spring Boot, Quarkus, etc.)
- Write clean, maintainable, and well-documented code
