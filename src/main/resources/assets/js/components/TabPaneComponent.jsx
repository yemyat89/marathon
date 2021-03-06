/** @jsx React.DOM */

define([
  "React"
], function(React) {
  return React.createClass({
    propTypes: {
      isActive: React.PropTypes.bool,
      onActivate: React.PropTypes.func
    },

    componentDidUpdate: function(prevProps, prevState) {
      if (!prevProps.isActive && this.props.isActive) {
        this.props.onActivate();
      }
    },

    getDefaultProps: function() {
      return {
        isActive: false,
        onActivate: function() {}
      };
    },

    render: function() {
      var classSet = React.addons.classSet({
        "active": this.props.isActive,
        "tab-pane": true
      });

      return (
        <div className={classSet}>
          {this.props.children}
        </div>
      );
    }
  });
});
